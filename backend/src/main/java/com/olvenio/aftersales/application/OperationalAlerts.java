package com.olvenio.aftersales.application;

import com.olvenio.shared.domain.ConflictException;
import com.olvenio.shared.domain.NotFoundException;
import com.olvenio.shared.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationalAlerts {
    private final JdbcTemplate db;
    private final int preparationHours;

    public OperationalAlerts(JdbcTemplate db, @Value("${app.after-sales.preparation-alert-hours:24}") int preparationHours) {
        if (preparationHours < 1 || preparationHours > 720)
            throw new IllegalStateException("APP_PREPARATION_ALERT_HOURS must be between 1 and 720");
        this.db = db;
        this.preparationHours = preparationHours;
    }

    public record Alert(String kind, UUID orderId, String reference, String detail, Instant since, long total) {}

    /** Return a bounded sample and the full count for each active type. */
    public List<Alert> active() {
        return db.query("""
                with alerts(kind, order_id, reference, detail, since) as (
                    select 'PAYMENT', id, order_number, null::varchar, updated_at from orders where status='PAYMENT_REVIEW'
                    union all
                    select 'ORDER_EMAIL', o.id, o.order_number, e.kind, e.next_attempt_at
                    from order_emails e join orders o on o.id=e.order_id
                    where e.status='FAILED' and o.status='PAID' and e.last_error is distinct from 'ORDER_NO_LONGER_ELIGIBLE'
                      and (e.kind='CONFIRMATION' or o.fulfillment_status in ('SHIPPED','DELIVERED'))
                    union all
                    select 'ACCOUNT_EMAIL', null::uuid, null::varchar, e.purpose, e.next_attempt_at
                    from account_emails e where e.status='FAILED' and e.last_error is distinct from 'EXPIRED'
                    union all
                    select 'PREPARATION', o.id, o.order_number, null::varchar, paid.since
                    from orders o cross join lateral (
                        select coalesce(max(created_at), o.placed_at) as since from order_events
                        where order_id=o.id and to_status='PAID' and from_status is distinct from 'PAID'
                    ) paid
                    where o.status='PAID' and o.fulfillment_status='UNFULFILLED'
                      and paid.since <= now() - make_interval(hours => ?)
                    union all
                    select 'RETURN', o.id, o.order_number, r.kind, r.created_at
                    from order_returns r join orders o on o.id=r.order_id where r.status in ('REQUESTED','APPROVED')
                    union all
                    select 'INVOICE', o.id, o.order_number, null::varchar, i.created_at
                    from order_invoices i join orders o on o.id=i.order_id where i.issued_at is null
                    union all
                    select 'LABEL', o.id, o.order_number, l.carrier, l.updated_at
                    from shipping_labels l join orders o on o.id=l.order_id
                    where l.status='REVIEW' or (l.status='PROCESSING' and l.updated_at < now() - interval '3 minutes')
                ), ranked as (
                    select *, count(*) over (partition by kind) as total,
                        row_number() over (partition by kind order by since asc, order_id asc) as rank from alerts
                )
                select kind,order_id,reference,detail,since,total from ranked where rank<=10 order by kind,rank
                """, (r, n) -> new Alert(r.getString("kind"), r.getObject("order_id", UUID.class), r.getString("reference"),
                        r.getString("detail"), r.getTimestamp("since").toInstant(), r.getLong("total")), preparationHours);
    }

    public enum OrderEmail { CONFIRMATION, SHIPMENT }

    @Transactional
    public void retryOrderEmail(UUID orderId, OrderEmail kind) {
        var status = db.query("select status,fulfillment_status from orders where id=? for update",
                (r, n) -> new String[]{r.getString(1), r.getString(2)}, orderId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No encontramos el pedido."));
        if (!"PAID".equals(status[0]) || (kind == OrderEmail.SHIPMENT && !List.of("SHIPPED", "DELIVERED").contains(status[1])))
            throw new ConflictException("El pedido ya no corresponde a ese aviso.");
        int updated = db.update("""
                update order_emails set status='PENDING', attempts=0, revision=revision+1, next_attempt_at=now(),
                    claimed_until=null, claim_id=null, last_error=null, sent_at=null
                where order_id=? and kind=? and status='FAILED' and last_error is distinct from 'ORDER_NO_LONGER_ELIGIBLE'
                """, orderId, kind.name());
        if (updated == 0) throw new ConflictException("Ese aviso ya no está pendiente de revisión.");
        db.update("""
                insert into audit_logs(id,actor_user_id,action,entity_type,entity_id,details,created_at)
                values(gen_random_uuid(),?,'ORDER_EMAIL_REQUEUED','ORDER',?,jsonb_build_object('kind',cast(? as text)),now())
                """, CurrentUser.id().orElseThrow(), orderId, kind.name());
    }
}
