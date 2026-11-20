package com.olvenio.orders.application;

import com.olvenio.orders.application.OrderDtos.OrderView;
import com.olvenio.orders.domain.Order;
import com.olvenio.orders.domain.OrderStatus;
import com.olvenio.orders.infrastructure.OrderRepository;
import com.olvenio.payments.application.ReservationService;
import com.olvenio.shared.domain.NotFoundException;
import com.olvenio.shared.domain.ConflictException;
import com.olvenio.shared.security.CurrentUser;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderAdminService {

    private final OrderRepository orders;
    private final ReservationService reservations;
    private final JdbcTemplate db;
    private final boolean production;

    public OrderAdminService(OrderRepository orders, ReservationService reservations, JdbcTemplate db, Environment env) {
        this.orders = orders;
        this.reservations = reservations;
        this.db = db;
        this.production = env.matchesProfiles("prod");
    }

    @Transactional
    public OrderView fulfill(UUID id, String status, String carrier, String trackingNumber) {
        Order order = orders.lockById(id).orElseThrow(() -> new NotFoundException("Order not found"));
        if ("SHIPPED".equals(status)) {
            if (Boolean.TRUE.equals(db.queryForObject("""
                    select exists (select 1 from shipping_labels where order_id=? and status in ('PROCESSING','REVIEW'))
                    """, Boolean.class, id)))
                throw new ConflictException("Revisa primero el intento de guía en Envia.");
            rejectSandboxGuide(id, trackingNumber);
        }
        order.fulfill(status, carrier, trackingNumber, CurrentUser.id().orElse(null), Instant.now());
        return OrderMapper.view(orders.saveAndFlush(order), true);
    }

    /** Repairs a logistics mistake; the shipment notice is sent again when it no longer matches reality. */
    @Transactional
    public OrderView correctFulfillment(UUID id, String status, String carrier, String trackingNumber, String note) {
        Order order = orders.lockById(id).orElseThrow(() -> new NotFoundException("Order not found"));
        rejectSandboxGuide(id, trackingNumber);
        boolean trackingChanged = order.correctFulfillment(status, carrier, trackingNumber, note,
                CurrentUser.id().orElse(null), Instant.now());
        OrderView view = OrderMapper.view(orders.saveAndFlush(order), true);
        boolean shipped = "SHIPPED".equals(order.getFulfillmentStatus()) || "DELIVERED".equals(order.getFulfillmentStatus());
        if (!shipped) {
            // Back before shipping: the next "shipped" must notify the customer again.
            db.update("delete from order_emails where order_id=? and kind='SHIPMENT'", id);
            db.update("update orders set shipping_email_sent_at=null where id=?", id);
        } else if (trackingChanged) {
            db.update("update order_emails set revision=revision+1,status='PENDING',attempts=0,next_attempt_at=now(),claimed_until=null,claim_id=null,sent_at=null,last_error=null where order_id=? and kind='SHIPMENT'", id);
            db.update("update orders set shipping_email_sent_at=null where id=?", id);
        }
        return view;
    }

    private void rejectSandboxGuide(UUID id, String trackingNumber) {
        if (production && trackingNumber != null && Boolean.TRUE.equals(db.queryForObject("""
                select exists (select 1 from shipping_labels where order_id=? and not live and lower(tracking_number)=lower(?))
                """, Boolean.class, id, trackingNumber.trim())))
            throw new ConflictException("La guía de pruebas de Envia no sirve para un envío real.");
    }

    /**
     * Network step before {@link #cancel}: closes the payment link of a pending order at the provider. Runs
     * outside any transaction so no row lock is held during provider calls.
     */
    public void prepareCancel(UUID id) {
        reservations.closeAtProvider(id);
    }

    /** Cancels a quote or pending order; reserved stock of a PENDING_PAYMENT order is returned. */
    @Transactional
    public OrderView cancel(UUID id, String note) {
        Order order = orders.lockById(id).orElseThrow(() -> new NotFoundException("Order not found"));
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            reservations.release(id, CurrentUser.id().orElse(null), note);
        } else {
            order.cancel(note, CurrentUser.id().orElse(null), Instant.now());
        }
        return OrderMapper.view(orders.saveAndFlush(order), true);
    }
}
