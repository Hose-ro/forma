package com.velora.payments.infrastructure;

import com.velora.payments.domain.*;
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
    public Optional<Payment> byOrder(UUID order) { return db.query("select * from payments where order_id=?",this::map,order).stream().findFirst(); }
    public Optional<Payment> find(UUID id) { return db.query("select * from payments where id=?",this::map,id).stream().findFirst(); }
    public Payment lock(UUID id) { return db.query("select * from payments where id=? for update",this::map,id).getFirst(); }
    public boolean keyBelongsToAnotherOrder(String key, UUID order) {
        return Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from payment_attempts a join payments p on p.id=a.payment_id where a.idempotency_key=? and p.order_id<>?)",Boolean.class,key,order));
    }
    public List<Map<String,Object>> list(int page) { return db.queryForList("select p.id,p.order_id,p.provider,p.status,p.amount,p.currency,p.created_at,p.provider_payment_id,o.order_number from payments p join orders o on o.id=p.order_id order by p.created_at desc,p.id limit 50 offset ?",page*50); }
    public List<Map<String,Object>> events(UUID id) { return db.queryForList("select event_type,processing_status,received_at,processed_at,payload::text as payload from payment_events where payment_id=? order by received_at desc limit 100",id); }
    public void event(UUID payment,String key,String type,String payload,String status) {
        db.update("insert into payment_events(id,payment_id,provider_event_id,event_type,payload,processing_status,processed_at) values (?,?,?,?,?::jsonb,?,now()) on conflict(provider_event_id) do nothing",UUID.randomUUID(),payment,key,type,payload,status);
    }
    public JdbcTemplate db() { return db; }
}
