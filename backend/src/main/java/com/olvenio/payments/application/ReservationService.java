package com.olvenio.payments.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.olvenio.catalog.domain.ProductVariant;
import com.olvenio.catalog.infrastructure.ProductVariantRepository;
import com.olvenio.orders.domain.Order;
import com.olvenio.orders.domain.OrderItem;
import com.olvenio.orders.domain.OrderStatus;
import com.olvenio.orders.infrastructure.OrderRepository;
import com.olvenio.payments.domain.Payment;
import com.olvenio.payments.infrastructure.MercadoPagoClient;
import com.olvenio.payments.infrastructure.PaymentProperties;
import com.olvenio.payments.infrastructure.PaymentRepository;
import com.olvenio.shared.domain.ConflictException;
import com.olvenio.shared.domain.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Returns the stock of unpaid orders: automatically once the reservation expires, or on an administrator's
 * cancellation. Stock is never released while the provider could still produce a valid charge: the preference is
 * closed first, every payment started from it is re-read, and any payment that is not finally rejected or
 * cancelled keeps the reservation.
 */
@Service
public class ReservationService {
    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    public enum Outcome { RELEASED, NOT_PENDING }

    private final OrderRepository orders;
    private final ProductVariantRepository variants;
    private final PaymentRepository payments;
    private final MercadoPagoClient client;
    private final PaymentVerificationService verification;
    private final PaymentProperties config;
    private final TransactionTemplate tx;

    public ReservationService(OrderRepository orders, ProductVariantRepository variants, PaymentRepository payments,
                              MercadoPagoClient client, PaymentVerificationService verification,
                              PaymentProperties config, TransactionTemplate tx) {
        this.orders = orders;
        this.variants = variants;
        this.payments = payments;
        this.client = client;
        this.verification = verification;
        this.config = config;
        this.tx = tx;
    }

    /** The grace period lets the provider settle payments started just before the preference expired. */
    @Scheduled(fixedDelayString = "${app.checkout.reservation-sweep-delay:120000}", initialDelay = 45000)
    public void sweep() {
        List<UUID> due = payments.db().queryForList("""
                select id from orders where status='PENDING_PAYMENT'
                    and coalesce(reservation_review_at, reservation_expires_at + interval '15 minutes') < now()
                    order by reservation_expires_at limit 20""", UUID.class);
        for (UUID id : due) {
            try {
                closeAtProvider(id);
                tx.executeWithoutResult(t -> release(id, null, "Reserva vencida sin pago"));
            } catch (RuntimeException e) {
                // A live payment or an unreachable provider keeps the reservation; look again later.
                payments.db().update("update orders set reservation_review_at=now()+interval '30 minutes' where id=?", id);
                log.warn("Reservation of order {} kept ({})", id, e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Network step, outside any transaction: closes the checkout link and refreshes every payment created from
     * it. Orders that never started a payment need nothing from the provider.
     */
    public void closeAtProvider(UUID orderId) {
        // Serialize the durable closing intent with creation/recovery, including an in-flight provider POST.
        // Provider reads/expiration happen after committing this intent, so no new creation can slip through.
        Payment p = tx.execute(t -> {
            Order order = orders.lockById(orderId).orElseThrow(() -> new NotFoundException("Order not found"));
            if (order.getStatus() != OrderStatus.PENDING_PAYMENT) return null;
            Payment current = payments.byOrder(orderId).orElse(null);
            if (current == null) return null;
            payments.lock(current.id());
            payments.closeCheckout(current.id());
            return payments.find(current.id()).orElseThrow();
        });
        if (p == null) return;
        config.requireConfigured();
        client.requireTestSeller();
        List<String> preferences = new ArrayList<>();
        if (p.providerPreferenceId() != null) {
            preferences.add(p.providerPreferenceId());
        } else {
            // The creation result was never known: a preference may or may not exist at the provider.
            if (p.createdAt().isAfter(Instant.now().minusSeconds(300)))
                throw new ConflictException("Payment initialization is still in progress; retry in a few minutes");
            JsonNode found = client.preferencesByReference(p.id()).path("elements");
            if (!found.isArray()) throw new MercadoPagoClient.ProviderException();
            found.forEach(e -> preferences.add(e.path("id").asText()));
        }
        for (String id : preferences) client.expirePreference(id);
        for (int offset = 0; offset < 200; offset += 50) {
            JsonNode results = client.search(p.id(), offset).path("results");
            if (!results.isArray()) throw new MercadoPagoClient.ProviderException();
            // Search only lists candidates; GET is the authoritative snapshot.
            for (JsonNode resource : results) verification.apply(client.payment(resource.path("id").asText()));
            if (results.size() < 50) break;
        }
        payments.event(p.id(), null, "PREFERENCE_CLOSED", "{}", "PROCESSED");
    }

    /**
     * Cancels a pending order and returns its stock. Must run inside the caller's transaction, after
     * {@link #closeAtProvider} when a payment was started.
     */
    public Outcome release(UUID orderId, UUID actor, String note) {
        Order order = orders.lockById(orderId).orElseThrow(() -> new NotFoundException("Order not found"));
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) return Outcome.NOT_PENDING;
        Payment p = payments.byOrder(orderId).orElse(null);
        if (p != null) {
            payments.lock(p.id());
            if (!payments.checkoutClosed(p.id()))
                throw new ConflictException("The payment link must be closed before releasing stock");
            Integer live = payments.db().queryForObject(
                    "select count(*) from payment_resources where payment_id=? and status not in ('REJECTED','CANCELLED')",
                    Integer.class, p.id());
            if (live != null && live > 0)
                throw new ConflictException("A payment for this order is still open at the provider; its stock stays reserved until it resolves");
            Boolean closed = payments.db().queryForObject(
                    "select exists(select 1 from payment_events where payment_id=? and event_type='PREFERENCE_CLOSED' and received_at>now()-interval '10 minutes')",
                    Boolean.class, p.id());
            if (!Boolean.TRUE.equals(closed))
                throw new ConflictException("The payment link must be closed at the provider before releasing stock");
            payments.db().update("update payments set status='CANCELLED',failure_reason='RESERVATION_RELEASED',updated_at=now() where id=?", p.id());
            payments.event(p.id(), null, "RESERVATION_RELEASED", "{}", "PROCESSED");
        }
        order.cancel(note, actor, Instant.now());
        var ids = order.getItems().stream().map(OrderItem::getVariantId).filter(Objects::nonNull).toList();
        Map<UUID, ProductVariant> locked = variants.lockAllById(ids).stream()
                .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));
        for (OrderItem item : order.getItems()) {
            ProductVariant v = item.getVariantId() == null ? null : locked.get(item.getVariantId());
            if (v != null) v.increaseStock(item.getQuantity());
        }
        orders.saveAndFlush(order);
        return Outcome.RELEASED;
    }
}
