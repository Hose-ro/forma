package com.olvenio.orders.infrastructure;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Transactional emails (payment confirmation, shipment notice) delivered from the order_emails outbox
 * (pending → processing → sent/failed, with leases, attempts and backoff). Active only when SMTP is configured
 * (SPRING_MAIL_HOST). Delivery is at-least-once and can never affect checkout or payment verification.
 */
@Component
@ConditionalOnProperty("spring.mail.host")
public class OrderMailer {
    private static final Logger log = LoggerFactory.getLogger(OrderMailer.class);
    private final JdbcTemplate db;
    private final JavaMailSender mail;
    private final String from;
    private final String notify;
    private final String store;
    private final String frontendUrl;

    public OrderMailer(JdbcTemplate db, JavaMailSender mail, @Value("${app.mail.from:}") String from,
                       @Value("${app.mail.notify:}") String notify, @Value("${app.store-name:Olvenio}") String store,
                       @Value("${app.payments.frontend-url:}") String frontendUrl) {
        if (from.isBlank()) throw new IllegalStateException("APP_MAIL_FROM is required when SPRING_MAIL_HOST is set");
        this.db = db;
        this.mail = mail;
        this.from = from;
        this.notify = notify;
        this.store = store;
        this.frontendUrl = frontendUrl.replaceAll("/$", "");
    }

    private static final int MAX_ATTEMPTS = 8;
    private static final String ORDER_COLUMNS = "select id,order_number,email,customer_first_name,subtotal,shipping_total,"
            + "total,currency,carrier,tracking_number,status,fulfillment_status from orders where id=?";

    @Scheduled(fixedDelayString = "${app.mail.poll-delay:60000}", initialDelay = 30000)
    public void deliver() {
        enqueue();
        // A claim is a lease, not a "sent" mark: if this process dies mid-send the lease expires and the
        // email is claimed again. "Sent" is only written after the SMTP server accepted the message.
        db.update("update order_emails set status='FAILED',last_error='CLAIM_EXPIRED' where status='PROCESSING' "
                + "and claimed_until<now() and attempts>=?", MAX_ATTEMPTS);
        var jobs = db.queryForList("""
                update order_emails e set status='PROCESSING', attempts=attempts+1, claimed_until=now()+interval '5 minutes'
                where e.id in (select id from order_emails where attempts<? and ((status='PENDING' and next_attempt_at<=now())
                        or (status='PROCESSING' and claimed_until<now()))
                    order by next_attempt_at limit 10 for update skip locked)
                returning e.id, e.order_id, e.kind, e.attempts""", MAX_ATTEMPTS);
        jobs.forEach(this::send);
    }

    private void enqueue() {
        db.update("insert into order_emails(id,order_id,kind) select gen_random_uuid(),id,'CONFIRMATION' from orders "
                + "where status='PAID' and confirmation_email_sent_at is null and placed_at > now() - interval '30 days' "
                + "on conflict (order_id,kind) do nothing");
        db.update("insert into order_emails(id,order_id,kind) select gen_random_uuid(),id,'SHIPMENT' from orders "
                + "where status='PAID' and shipping_email_sent_at is null and fulfillment_status in ('SHIPPED','DELIVERED') "
                + "and placed_at > now() - interval '30 days' on conflict (order_id,kind) do nothing");
    }

    private void send(Map<String, Object> job) {
        Object jobId = job.get("id");
        Object id = job.get("order_id");
        boolean confirmation = "CONFIRMATION".equals(job.get("kind"));
        int attempts = ((Number) job.get("attempts")).intValue();
        try {
            Map<String, Object> o = db.queryForMap(ORDER_COLUMNS, id);
            boolean due = "PAID".equals(o.get("status")) && (confirmation
                    || java.util.List.of("SHIPPED", "DELIVERED").contains(o.get("fulfillment_status")));
            if (!due) {
                db.update("update order_emails set status='FAILED',last_error='ORDER_NO_LONGER_ELIGIBLE',claimed_until=null where id=?", jobId);
                return;
            }
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(from);
            m.setTo((String) o.get("email"));
            if (!notify.isBlank()) {
                m.setReplyTo(notify);
                if (confirmation) m.setBcc(notify);
            }
            String number = (String) o.get("order_number");
            m.setSubject((confirmation ? "Confirmación de tu pedido " : "Tu pedido va en camino · ") + number + " · " + store);
            m.setText(confirmation ? confirmation(o) : shipment(o));
            mail.send(m);
            // If the process dies between the SMTP acceptance and these updates the email is sent once more
            // after the lease expires: a rare duplicate is preferred over a silently lost notice.
            db.update("update order_emails set status='SENT',sent_at=now(),claimed_until=null,last_error=null where id=?", jobId);
            db.update("update orders set " + (confirmation ? "confirmation_email_sent_at" : "shipping_email_sent_at")
                    + "=now() where id=?", id);
        } catch (RuntimeException e) {
            boolean exhausted = attempts >= MAX_ATTEMPTS;
            long minutes = Math.min(360, 1L << Math.min(attempts, 9));
            db.update("update order_emails set status=?,claimed_until=null,last_error=?,"
                            + "next_attempt_at=now()+make_interval(mins => ?) where id=?",
                    exhausted ? "FAILED" : "PENDING", e.getClass().getSimpleName(), (int) minutes, jobId);
            if (exhausted) log.error("Order email for {} failed permanently after {} attempts", id, attempts);
            else log.warn("Order email deferred for {} ({}), attempt {}", id, e.getClass().getSimpleName(), attempts);
        }
    }

    private String confirmation(Map<String, Object> o) {
        StringBuilder t = new StringBuilder("Hola ").append(o.get("customer_first_name")).append(",\n\n")
                .append("Recibimos tu pago. Este es el resumen de tu pedido ").append(o.get("order_number")).append(":\n\n");
        for (var i : db.queryForList("select product_name,variant_name,quantity,line_total from order_items where order_id=? order by position", o.get("id")))
            t.append("- ").append(i.get("product_name")).append(" (").append(i.get("variant_name")).append(") x")
                    .append(i.get("quantity")).append(" — ").append(money(i.get("line_total"), o)).append('\n');
        t.append("\nSubtotal: ").append(money(o.get("subtotal"), o))
                .append("\nEnvío: ").append(money(o.get("shipping_total"), o))
                .append("\nTotal pagado: ").append(money(o.get("total"), o)).append("\n\n");
        for (var a : db.queryForList("select recipient_name,line1,line2,city,state,postal_code from order_addresses where order_id=? and type='SHIPPING'", o.get("id")))
            t.append("Enviaremos a:\n").append(a.get("recipient_name")).append('\n').append(a.get("line1"))
                    .append(a.get("line2") == null ? "" : ", " + a.get("line2")).append('\n').append(a.get("city")).append(", ")
                    .append(a.get("state")).append(", C.P. ").append(a.get("postal_code")).append("\n\n");
        t.append("Te avisaremos por este medio cuando tu pedido salga a reparto.\n");
        return t.append(footer(o)).toString();
    }

    private String shipment(Map<String, Object> o) {
        StringBuilder t = new StringBuilder("Hola ").append(o.get("customer_first_name")).append(",\n\n")
                .append("Tu pedido ").append(o.get("order_number")).append(" ya fue enviado.\n");
        if (o.get("carrier") != null) t.append("Paquetería: ").append(o.get("carrier")).append('\n');
        if (o.get("tracking_number") != null) t.append("Número de guía: ").append(o.get("tracking_number")).append('\n');
        return t.append(footer(o)).toString();
    }

    private String footer(Map<String, Object> o) {
        return (frontendUrl.isBlank() ? "" : "\nConsulta tu pedido: " + frontendUrl + "/account/orders/" + o.get("id") + "\n")
                + "\nSi tienes dudas, responde a este correo.\n\n" + store + "\n";
    }

    private static String money(Object amount, Map<String, Object> o) {
        return "$" + ((BigDecimal) amount).setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + o.get("currency");
    }
}
