package com.olvenio.marketing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
class MarketingDelivery {
    private static final Logger log = LoggerFactory.getLogger(MarketingDelivery.class);
    private static final int MAX_ATTEMPTS = 4;
    private final TikTokSettings settings;
    private final TikTokEventsClient client;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    MarketingDelivery(TikTokSettings settings, TikTokEventsClient client, JdbcTemplate db, ObjectMapper json) {
        this.settings = settings;
        this.client = client;
        this.db = db;
        this.json = json;
    }

    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler marketingScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("marketing-");
        return scheduler;
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 5000, scheduler = "marketingScheduler")
    public void poll() {
        if (!settings.serverEnabled) return;
        try {
            preparePurchases();
            for (int i = 0; i < 12 && !Thread.currentThread().isInterrupted(); i++) {
                var claim = claim();
                if (claim == null) break;
                deliver(claim);
            }
        } catch (RuntimeException ex) {
            log.warn("marketing outcome=worker_error error_type={}", ex.getClass().getSimpleName());
        }
    }

    // Only the verified payment path can persist both PAID and an approved matching provider payment.
    // Reading committed state lets retries recover without running any marketing work in the payment transaction.
    void preparePurchases() {
        var inserted = db.queryForList("""
                INSERT INTO marketing_events
                    (event_id, pixel_id, event_name, consent_id, order_id, occurred_at,
                     page_url, properties, ttp, ttclid)
                SELECT encode(sha256(convert_to('Purchase:' || o.id::text, 'UTF8')), 'hex'),
                       placed.pixel_id, 'Purchase', placed.consent_id, o.id, p.approved_at,
                       placed.page_url, placed.properties, placed.ttp, placed.ttclid
                FROM payments p JOIN orders o ON o.id = p.order_id
                JOIN marketing_events placed ON placed.order_id = o.id AND placed.event_name = 'PlaceAnOrder'
                JOIN marketing_consents c ON c.id = placed.consent_id
                WHERE placed.pixel_id = ?
                  AND p.status = 'APPROVED' AND p.approved_at > now() - interval '24 hours'
                  AND o.status = 'PAID' AND o.payment_status = 'APPROVED'
                  AND o.total = p.amount AND o.currency = p.currency
                  AND c.revoked_at IS NULL AND c.expires_at > now()
                  AND (placed.ttp IS NOT NULL OR placed.ttclid IS NOT NULL)
                  AND NOT EXISTS (SELECT 1 FROM marketing_events e WHERE e.pixel_id = placed.pixel_id
                                    AND e.order_id = o.id AND e.event_name = 'Purchase')
                ORDER BY p.approved_at LIMIT 50 FOR SHARE OF c
                ON CONFLICT DO NOTHING
                RETURNING event_id
                """, String.class, settings.pixel);
        for (String id : inserted) log.info("marketing outcome=prepared event=Purchase event_id={}", id);
    }

    private record Claim(String id, String name, UUID lease, int attempts) {}

    private Claim claim() {
        UUID lease = UUID.randomUUID();
        return db.query("""
                UPDATE marketing_events e SET status = 'SENDING', attempts = e.attempts + 1,
                       claim_id = ?, due_at = now() + interval '30 seconds'
                WHERE e.event_id = (
                    SELECT next.event_id FROM marketing_events next
                    JOIN marketing_consents c ON c.id = next.consent_id
                    WHERE next.pixel_id = ? AND next.status IN ('PENDING', 'SENDING') AND next.due_at <= now()
                      AND next.attempts < ? AND next.occurred_at > now() - interval '24 hours'
                      AND c.revoked_at IS NULL AND c.expires_at > now()
                    ORDER BY next.due_at FOR UPDATE OF next SKIP LOCKED LIMIT 1)
                RETURNING e.event_id, e.event_name, e.attempts
                """, (row, index) -> new Claim(row.getString(1), row.getString(2), lease, row.getInt(3)),
                lease, settings.pixel, MAX_ATTEMPTS).stream().findFirst().orElse(null);
    }

    private void deliver(Claim claim) {
        // Check consent again just before transmission, including retries and work leased by another instance.
        var event = db.query("""
                SELECT e.* FROM marketing_events e JOIN marketing_consents c ON c.id = e.consent_id
                WHERE e.event_id = ? AND e.claim_id = ? AND e.status = 'SENDING'
                  AND c.revoked_at IS NULL AND c.expires_at > now() AND (e.ttp IS NOT NULL OR e.ttclid IS NOT NULL)
                  AND (e.event_name <> 'Purchase' OR EXISTS (
                      SELECT 1 FROM orders o JOIN payments p ON p.order_id = o.id
                      WHERE o.id = e.order_id AND o.status = 'PAID' AND o.payment_status = 'APPROVED'
                        AND p.status = 'APPROVED'))
                """, (row, index) -> {
            try {
                return new TikTokEventsClient.Event(row.getString("event_id"), row.getString("event_name"),
                        row.getTimestamp("occurred_at").toInstant().getEpochSecond(), row.getString("page_url"),
                        json.readTree(row.getString("properties")), row.getString("ttp"), row.getString("ttclid"));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Invalid marketing snapshot");
            }
        }, claim.id, claim.lease).stream().findFirst().orElse(null);
        if (event == null) {
            finish(claim, "SKIPPED", 0);
            return;
        }
        try {
            var result = client.send(event);
            if (result.sent()) finish(claim, "SENT", 0);
            else if (result.retry()) retry(claim);
            else finish(claim, "REJECTED", 0);
            log.info("marketing outcome=provider_response event={} event_id={} http_status={} api_code={} attempt={}",
                    claim.name, claim.id, result.httpStatus(), result.apiCode(), claim.attempts);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (java.io.IOException | ExecutionException | TimeoutException ex) {
            log.warn("marketing outcome=communication_error event={} event_id={} error_type={} attempt={}",
                    claim.name, claim.id, ex.getClass().getSimpleName(), claim.attempts);
            retry(claim);
        }
    }

    private void retry(Claim claim) {
        if (claim.attempts >= MAX_ATTEMPTS) finish(claim, "REJECTED", 0);
        else finish(claim, "PENDING", 15 * (1 << (claim.attempts - 1)));
    }

    private void finish(Claim claim, String status, int backoff) {
        int changed = db.update("""
                UPDATE marketing_events SET status = ?, due_at = now() + (? * interval '1 second'), claim_id = NULL
                WHERE event_id = ? AND claim_id = ?
                """, status, backoff, claim.id, claim.lease);
        if (changed > 0) log.info("marketing outcome={} event={} event_id={} attempt={}",
                "PENDING".equals(status) ? "retry" : status.toLowerCase(java.util.Locale.ROOT),
                claim.name, claim.id, claim.attempts);
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000, scheduler = "marketingScheduler")
    public void prune() {
        try {
            db.update("""
                    UPDATE marketing_events SET status = 'REJECTED', claim_id = NULL
                    WHERE status = 'SENDING' AND attempts >= ? AND due_at <= now()
                    """, MAX_ATTEMPTS);
            db.update("""
                    UPDATE marketing_events SET status = 'SKIPPED', claim_id = NULL
                    WHERE status IN ('PENDING', 'SENDING') AND occurred_at <= now() - interval '24 hours'
                    """);
            db.update("DELETE FROM marketing_events WHERE occurred_at <= now() - interval '30 days'");
            db.update("DELETE FROM marketing_consents WHERE expires_at <= now() OR revoked_at <= now() - interval '30 days'");
        } catch (RuntimeException ex) {
            log.warn("marketing outcome=retention_error error_type={}", ex.getClass().getSimpleName());
        }
    }
}
