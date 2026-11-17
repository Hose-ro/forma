package com.olvenio.payments.infrastructure;

import com.olvenio.payments.domain.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {
    private final JdbcTemplate db;
    public PaymentRepository(JdbcTemplate db) { this.db=db; }
    private Payment map(ResultSet r, int row) throws SQLException {
        var approved = r.getTimestamp("approved_at");
        return new Payment(r.getObject("id",UUID.class),r.getObject("order_id",UUID.class),r.getString("provider"),
            r.getString("provider_payment_id"),r.getString("provider_preference_id"),PaymentStatus.valueOf(r.getString("status")),
            r.getBigDecimal("amount"),r.getString("currency"),r.getString("checkout_url"),r.getTimestamp("created_at").toInstant(),
            approved==null?null:approved.toInstant(),r.getString("failure_reason"));
    }
    /** The checkout a buyer can still pay; at most one per order (unique partial index). */
    public Optional<Payment> openByOrder(UUID order) { return db.query("select * from payments where order_id=? and checkout_closed_at is null",this::map,order).stream().findFirst(); }
    /** Every attempt of the order, oldest first. */
    public List<Payment> allByOrder(UUID order) { return db.query("select * from payments where order_id=? order by created_at,id",this::map,order); }
    /** The attempt that best describes the order: one holding money if any, else the newest. */
    public Optional<Payment> latestByOrder(UUID order) {
        return db.query("select * from payments where order_id=? order by status in ('APPROVED','PARTIALLY_REFUNDED','REFUNDED','UNKNOWN') desc,created_at desc,id limit 1",this::map,order).stream().findFirst();
    }
    /** Attempts closed locally whose provider has not yet confirmed the close. */
    public List<Payment> pendingProviderClose(UUID order) {
        return db.query("select * from payments where order_id=? and checkout_closed_at is not null and provider_closed_at is null order by created_at,id",this::map,order);
    }
    /** Called after the provider confirmed the close and every charge was re-read. */
    public void providerClosed(UUID id) {
        db.update("update payments set provider_closed_at=now() where id=?", id);
    }
    /** A charge of any attempt that is not finally rejected or cancelled: the buyer may still be paying. */
    public boolean liveChargeForOrder(UUID order) {
        return Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from payment_resources r join payments p on p.id=r.payment_id where p.order_id=? and r.status not in ('REJECTED','CANCELLED'))",Boolean.class,order));
    }
    public boolean keyUsed(String key) {
        return Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from payment_attempts where idempotency_key=?)",Boolean.class,key));
    }
    public Optional<Payment> find(UUID id) { return db.query("select * from payments where id=?",this::map,id).stream().findFirst(); }
    public Payment lock(UUID id) { return db.query("select * from payments where id=? for update",this::map,id).getFirst(); }
    public boolean checkoutClosed(UUID id) {
        return Boolean.TRUE.equals(db.queryForObject("select checkout_closed_at is not null from payments where id=?", Boolean.class, id));
    }
    public enum CloseReason { SWITCH, ORDER }
    /** Caller holds the order and payment locks. This intent survives provider/network failures. */
    public void closeCheckout(UUID id, CloseReason reason) {
        db.update("update payments set checkout_closed_at=coalesce(checkout_closed_at,now()),close_reason=case when checkout_closed_at is null or ?='ORDER' then ? else close_reason end where id=?",
                reason.name(), reason.name(), id);
    }
    /** The order is being cancelled, expired or paid: no new checkout may be opened for it. */
    public boolean orderClosing(UUID order) {
        return Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from payments where order_id=? and close_reason='ORDER')",Boolean.class,order));
    }
    public boolean keyBelongsToAnotherOrder(String key, UUID order) {
        return Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from payment_attempts a join payments p on p.id=a.payment_id where a.idempotency_key=? and p.order_id<>?)",Boolean.class,key,order));
    }
    public List<Map<String,Object>> list(int page) { return db.queryForList("select p.id,p.order_id,p.provider,p.status,p.amount,p.currency,p.created_at,p.provider_payment_id,p.checkout_closed_at,o.order_number from payments p join orders o on o.id=p.order_id order by p.created_at desc,p.id limit 50 offset ?",page*50); }
    /** Admin view of every checkout attempt of an order, oldest first. */
    public List<Map<String,Object>> attempts(UUID order) {
        return db.queryForList("select id,provider,status,amount,currency,created_at,provider_payment_id,provider_preference_id,checkout_closed_at,close_reason,provider_closed_at,failure_reason from payments where order_id=? order by created_at,id",order);
    }
    /** Verified events of all attempts of an order, newest first. */
    public List<Map<String,Object>> orderEvents(UUID order) {
        return db.queryForList("select p.provider,e.event_type,e.processing_status,e.received_at,e.processed_at,e.payload::text as payload from payment_events e join payments p on p.id=e.payment_id where p.order_id=? order by e.received_at desc limit 200",order);
    }
    public List<Map<String,Object>> events(UUID id) { return db.queryForList("select event_type,processing_status,received_at,processed_at,payload::text as payload from payment_events where payment_id=? order by received_at desc limit 100",id); }
    public void event(UUID payment,String key,String type,String payload,String status) {
        db.update("insert into payment_events(id,payment_id,provider_event_id,event_type,payload,processing_status,processed_at) values (?,?,?,?,?::jsonb,?,now()) on conflict(provider_event_id) do nothing",UUID.randomUUID(),payment,key,type,payload,status);
    }
    public JdbcTemplate db() { return db; }
}
