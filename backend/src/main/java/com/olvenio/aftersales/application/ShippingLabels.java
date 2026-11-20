package com.olvenio.aftersales.application;

import com.olvenio.aftersales.infrastructure.EnviaClient;
import com.olvenio.aftersales.infrastructure.EnviaClient.Destination;
import com.olvenio.aftersales.infrastructure.EnviaClient.Parcel;
import com.olvenio.aftersales.infrastructure.EnviaClient.Rate;
import com.olvenio.orders.domain.Order;
import com.olvenio.orders.domain.OrderStatus;
import com.olvenio.orders.infrastructure.OrderRepository;
import com.olvenio.shared.domain.ConflictException;
import com.olvenio.shared.domain.NotFoundException;
import com.olvenio.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ShippingLabels {
    private static final Logger log = LoggerFactory.getLogger(ShippingLabels.class);
    private final OrderRepository orders;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final EnviaClient envia;

    public ShippingLabels(OrderRepository orders, JdbcTemplate db, TransactionTemplate tx, EnviaClient envia) {
        this.orders = orders;
        this.db = db;
        this.tx = tx;
        this.envia = envia;
    }

    public record ParcelInput(@NotNull @DecimalMin("0.05") @DecimalMax("70") @Digits(integer = 2, fraction = 2) BigDecimal weight,
                              @Min(1) @Max(150) int length, @Min(1) @Max(150) int width, @Min(1) @Max(150) int height,
                              @NotBlank @Size(max = 100) String content) {
        Parcel parcel() { return new Parcel(weight, length, width, height, content.trim()); }
    }
    public record Purchase(@Valid @NotNull ParcelInput parcel,
                           @NotNull @Pattern(regexp = "[a-z0-9_-]{2,40}") String carrier,
                           @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String service,
                           @NotNull @DecimalMin("0.01") @DecimalMax("999999") @Digits(integer = 6, fraction = 4) BigDecimal amount) {}
    public record Recovery(@NotBlank @Size(max = 100) String shipmentId,
                           @NotBlank @Size(max = 120) String trackingNumber,
                           @NotBlank @Size(max = 2048) String labelUrl) {}
    public record LabelView(boolean live, String status, String carrier, String service, String shipmentId,
                            String trackingNumber, String labelUrl) {}
    public record View(boolean enabled, boolean live, LabelView label) {}
    private record Snapshot(Destination destination, BigDecimal goods, String number) {}

    public View view(UUID orderId) {
        if (!orders.existsById(orderId)) throw missing();
        return new View(envia.enabled(), envia.live(), current(orderId));
    }

    public List<Rate> quotes(UUID orderId, ParcelInput input) {
        var snap = tx.execute(t -> snapshot(find(orderId, false)));
        return envia.rates(envia.destination(snap.destination()), input.parcel(), snap.goods());
    }

    public LabelView purchase(UUID orderId, Purchase request) {
        LabelView existing = current(orderId);
        if (existing != null && !"ABANDONED".equals(existing.status())) return existing;
        var snap = tx.execute(t -> snapshot(find(orderId, false)));
        var destination = envia.destination(snap.destination());
        var parcel = request.parcel().parcel();
        var rate = envia.rates(destination, parcel, snap.goods()).stream()
                .filter(r -> r.carrier().equals(request.carrier()) && r.service().equals(request.service()))
                .filter(r -> r.amount().compareTo(request.amount()) == 0).findFirst()
                .orElseThrow(() -> new ConflictException("Cambió la tarifa. Vuelve a cotizar antes de generar la guía."));
        UUID requestId = UUID.randomUUID();
        boolean claimed = Boolean.TRUE.equals(tx.execute(t -> {
            Order order = find(orderId, true);
            if (!snap.equals(snapshot(order)))
                throw new ConflictException("El pedido cambió. Vuelve a cotizar antes de generar la guía.");
            return db.update("""
                    insert into shipping_labels(order_id,request_id,status,carrier,service,created_by,live)
                    values (?,?,'PROCESSING',?,?,?,?)
                    on conflict(order_id) do update set status='PROCESSING',request_id=excluded.request_id,
                        carrier=excluded.carrier,service=excluded.service,created_by=excluded.created_by,live=excluded.live,created_at=now(),
                        updated_at=now(),shipment_id=null,label_url=null,tracking_number=null,tracking_url=null
                    where shipping_labels.status='ABANDONED'
                    """, orderId, requestId, rate.carrier(), rate.service(), CurrentUser.id().orElseThrow(), envia.live()) == 1;
        }));
        if (!claimed) return current(orderId);
        try {
            var label = envia.generate(destination, parcel, snap.goods(), rate, snap.number() + "/" + requestId);
            db.update("""
                    update shipping_labels set status='READY',shipment_id=?,label_url=?,tracking_number=?,tracking_url=?,updated_at=now()
                    where order_id=? and request_id=? and status='PROCESSING'
                    """, label.shipmentId(), label.url(), label.trackingNumber(), label.trackingUrl(), orderId, requestId);
        } catch (RuntimeException e) {
            log.warn("Envia label request needs review, order {}, request {} ({})", orderId, requestId, e.getClass().getSimpleName());
            db.update("update shipping_labels set status='REVIEW',updated_at=now() where order_id=? and request_id=? and status='PROCESSING'",
                    orderId, requestId);
        }
        return current(orderId);
    }

    /** The operator checked Envia and confirmed that no active label or charge exists. */
    public LabelView clearUncertain(UUID orderId, String reason) {
        return tx.execute(t -> {
            find(orderId, true);
            int changed = db.update("""
                    update shipping_labels set status='ABANDONED',updated_at=now() where order_id=? and
                      (status='REVIEW' or (status='PROCESSING' and updated_at < now() - interval '3 minutes'))
                    """, orderId);
            if (changed == 0) throw new ConflictException("No hay un intento pendiente de revisión.");
            db.update("""
                    insert into audit_logs(id, actor_user_id, action, entity_type, entity_id, details, created_at)
                    values(gen_random_uuid(),?,'SHIPPING_LABEL_CLEARED','ORDER',?,jsonb_build_object('reason',cast(? as text)),now())
                    """, CurrentUser.id().orElseThrow(), orderId, reason.trim());
            return current(orderId);
        });
    }

    public LabelView recover(UUID orderId, Recovery recovery) {
        String url = EnviaClient.httpsUrl(recovery.labelUrl().trim());
        if (url == null || !java.net.URI.create(url).getHost().matches(
                "(?i)(?:[a-z0-9-]+[.])*envia[.]com|envia[-a-z0-9]*[.]s3(?:[.-][a-z0-9-]+)?[.]amazonaws[.]com"))
            throw new com.olvenio.shared.domain.InvalidRequestException("Usa el enlace HTTPS del PDF que muestra Envia.");
        return tx.execute(t -> {
            find(orderId, true);
            int changed = db.update("""
                    update shipping_labels set status='READY',shipment_id=?,tracking_number=?,label_url=?,updated_at=now()
                    where order_id=? and (status='REVIEW' or (status='PROCESSING' and updated_at < now() - interval '3 minutes'))
                    """, recovery.shipmentId().trim(), recovery.trackingNumber().trim(), url, orderId);
            if (changed == 0) throw new ConflictException("No hay un intento pendiente de revisión.");
            db.update("""
                    insert into audit_logs(id,actor_user_id,action,entity_type,entity_id,details,created_at)
                    values(gen_random_uuid(),?,'SHIPPING_LABEL_RECOVERED','ORDER',?,jsonb_build_object('shipment',cast(? as text)),now())
                    """, CurrentUser.id().orElseThrow(), orderId, recovery.shipmentId().trim());
            return current(orderId);
        });
    }

    private LabelView current(UUID orderId) {
        return db.query("""
                select case when status='PROCESSING' and updated_at < now() - interval '3 minutes'
                    then 'REVIEW' else status end as displayed_status, carrier, service, shipment_id, tracking_number, label_url, live
                from shipping_labels where order_id=?
                """, (r, n) -> new LabelView(r.getBoolean("live"), r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                r.getString(5), r.getString(6)), orderId).stream().findFirst().orElse(null);
    }

    private Order find(UUID id, boolean lock) {
        return (lock ? orders.lockById(id) : orders.findById(id)).orElseThrow(ShippingLabels::missing);
    }

    private static Snapshot snapshot(Order order) {
        if (order.getStatus() != OrderStatus.PAID || !List.of("UNFULFILLED", "PREPARING").contains(order.getFulfillmentStatus()))
            throw new ConflictException("Solo puedes generar guías de pedidos pagados que están por enviar.");
        var address = order.shippingAddress().orElseThrow(() -> new ConflictException("El pedido no tiene dirección de envío."));
        return new Snapshot(Destination.from(address, order.getCustomerPhone(), order.getEmail()),
                order.getSubtotal(), order.getOrderNumber());
    }

    private static NotFoundException missing() { return new NotFoundException("No encontramos el pedido."); }
}
