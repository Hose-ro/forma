package com.olvenio.marketing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olvenio.cart.application.CartDtos.CartView;
import com.olvenio.catalog.application.CatalogDtos.ProductSummaryView;
import com.olvenio.orders.application.OrderDtos.OrderView;
import com.olvenio.orders.domain.OrderStatus;
import com.olvenio.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MarketingTracking {
    public static final String EVENT_HEADER = "X-Olvenio-Marketing-Event";
    static final String PURCHASE = "Purchase";
    private static final Logger log = LoggerFactory.getLogger(MarketingTracking.class);

    private final TikTokSettings settings;
    private final TikTokConsent consent;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    MarketingTracking(TikTokSettings settings, TikTokConsent consent, JdbcTemplate db, ObjectMapper json) {
        this.settings = settings;
        this.consent = consent;
        this.db = db;
        this.json = json;
    }

    public void cartAdded(HttpServletRequest request, HttpServletResponse response, CartView cart,
                          UUID variantId, int quantity) {
        capture("AddToCart", request, response, null, Instant.now(), () -> cart.items().stream()
                .filter(line -> variantId.equals(line.variantId()))
                .findFirst().map(line -> commerce(cart.currency(), line.unitPrice().multiply(BigDecimal.valueOf(quantity)),
                        List.of(new Content(variantId.toString(), line.unitPrice(), quantity)))).orElse(null));
    }

    public void wishlistAdded(HttpServletRequest request, HttpServletResponse response, ProductSummaryView product) {
        capture("AddToWishlist", request, response, null, Instant.now(), () ->
                product.defaultVariantId() == null ? Map.of() : Map.of("content_type", "product",
                        "contents", List.of(Map.of("content_id", product.defaultVariantId().toString()))));
    }

    public void checkoutStarted(HttpServletRequest request, HttpServletResponse response, OrderView quote) {
        capture("InitiateCheckout", request, response, quote.id(), quote.createdAt(), () -> commerce(quote));
    }

    public void orderPlaced(HttpServletRequest request, HttpServletResponse response, OrderView order) {
        capture("PlaceAnOrder", request, response, order.id(), order.placedAt(), () -> commerce(order));
    }

    public void registered(HttpServletRequest request, HttpServletResponse response, UUID userId) {
        // The UUID seeds an opaque event ID and is never included as a customer match key.
        capture("CompleteRegistration", request, response, null, Instant.now(), Map::of, userId);
    }

    public void authorizedPurchase(HttpServletRequest request, HttpServletResponse response, OrderView order) {
        if (!settings.enabled || order.status() != OrderStatus.PAID || !"APPROVED".equals(order.paymentStatus())
                || CurrentUser.isAdmin()) return;
        String path = consent.page(request);
        if (!isOrderPage(path, order.id())) return;
        try {
            var grant = consent.grant(request);
            if (grant == null) return;
            Boolean eligible = db.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM marketing_events e JOIN payments p ON p.order_id = e.order_id
                        WHERE e.event_name = 'PlaceAnOrder' AND e.order_id = ? AND e.pixel_id = ?
                          AND e.consent_id = ? AND p.status = 'APPROVED'
                          AND p.approved_at > now() - interval '24 hours'
                          AND p.amount = ? AND p.currency = ?
                    )
                    """, Boolean.class, order.id(), settings.pixel, grant.id(), order.total(), order.currency());
            if (Boolean.TRUE.equals(eligible)) response.setHeader(EVENT_HEADER, eventId(PURCHASE, order.id()));
        } catch (RuntimeException ex) {
            log.warn("marketing outcome=receipt_error event={} error_type={}", PURCHASE, ex.getClass().getSimpleName());
        }
    }

    private static boolean isOrderPage(String path, UUID orderId) {
        return ("/checkout/confirmation/" + orderId).equals(path) || ("/account/orders/" + orderId).equals(path)
                || "/checkout/success".equals(path) || "/checkout/pending".equals(path) || "/checkout/failure".equals(path)
                // PayPal returns here and captures the payment on this page.
                || "/checkout/paypal".equals(path);
    }

    private void capture(String name, HttpServletRequest request, HttpServletResponse response,
                         UUID orderId, Instant occurredAt, Supplier<Map<String, Object>> properties) {
        capture(name, request, response, orderId, occurredAt, properties, orderId == null ? UUID.randomUUID() : orderId);
    }

    private void capture(String name, HttpServletRequest request, HttpServletResponse response,
                         UUID orderId, Instant occurredAt, Supplier<Map<String, Object>> properties, UUID key) {
        if (!settings.enabled) return;
        String path = eventPage(name, consent.page(request));
        if (path == null) return;
        String id = eventId(name, key);
        try {
            var grant = consent.grant(request);
            if (grant == null) return;
            var snapshot = properties.get();
            if (snapshot == null) return;
            boolean attributable = grant.ttp() != null || grant.ttclid() != null;
            int inserted = db.update("""
                    INSERT INTO marketing_events
                        (event_id, pixel_id, event_name, consent_id, order_id, occurred_at, page_url,
                         properties, ttp, ttclid, status)
                    SELECT ?, ?, ?, c.id, ?, ?, ?, CAST(? AS jsonb), ?, c.ttclid,
                           CASE WHEN ? OR c.ttclid IS NOT NULL THEN 'PENDING' ELSE 'SKIPPED' END
                    FROM marketing_consents c
                    WHERE c.id = ? AND c.revoked_at IS NULL AND c.expires_at > now()
                    FOR SHARE OF c
                    ON CONFLICT DO NOTHING
                    """, id, settings.pixel, name, orderId, Timestamp.from(occurredAt), settings.origin + path,
                    json.writeValueAsString(snapshot), grant.ttp(), grant.ttp() != null, grant.id());
            if (inserted > 0) {
                log.info("marketing outcome={} event={} event_id={}",
                        attributable ? "prepared" : "prepared_browser_only", name, id);
                response.setHeader(EVENT_HEADER, id);
            } else {
                log.debug("marketing outcome=duplicate_or_inactive event={} event_id={}", name, id);
            }
        } catch (JsonProcessingException | RuntimeException ex) {
            log.warn("marketing outcome=prepare_error event={} error_type={}", name, ex.getClass().getSimpleName());
        }
    }

    static String eventPage(String name, String path) {
        if (path == null) return null;
        return switch (name) {
            case "AddToCart", "AddToWishlist" -> path.equals("/") || path.equals("/shop") || path.equals("/cart")
                    || path.matches("/product/[a-z0-9-]{1,180}") ? path : null;
            case "InitiateCheckout", "PlaceAnOrder" -> "/checkout".equals(path) ? path : null;
            case "CompleteRegistration" -> "/account".equals(path) || "/checkout".equals(path) ? path : null;
            default -> null;
        };
    }

    private static Map<String, Object> commerce(OrderView order) {
        return commerce(order.currency(), order.total(), order.items().stream()
                .filter(item -> item.variantId() != null)
                .map(item -> new Content(item.variantId().toString(), item.unitPrice(), item.quantity())).toList());
    }

    private static Map<String, Object> commerce(String currency, BigDecimal total, List<Content> items) {
        return items.isEmpty() ? Map.of("currency", currency, "value", total)
                : Map.of("currency", currency, "value", total, "content_type", "product", "contents", items);
    }

    private record Content(String content_id, BigDecimal price, int quantity) {}

    static String eventId(String name, UUID key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((name + ":" + key).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
