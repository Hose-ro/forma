package com.velora.orders.infrastructure;

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
 * Transactional emails (payment confirmation, shipment notice) delivered from an outbox on the orders table.
 * Active only when SMTP is configured (SPRING_MAIL_HOST). A failed delivery is retried on the next poll and can
 * never affect checkout or payment verification.
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
                       @Value("${app.mail.notify:}") String notify, @Value("${app.store-name:FORMA.}") String store,
                       @Value("${app.payments.frontend-url:}") String frontendUrl) {
        if (from.isBlank()) throw new IllegalStateException("APP_MAIL_FROM is required when SPRING_MAIL_HOST is set");
        this.db = db;
        this.mail = mail;
        this.from = from;
        this.notify = notify;
        this.store = store;
        this.frontendUrl = frontendUrl.replaceAll("/$", "");
    }

    @Scheduled(fixedDelayString = "${app.mail.poll-delay:60000}", initialDelay = 30000)
    public void deliver() {
        pending("confirmation_email_sent_at", "").forEach(o -> send("confirmation_email_sent_at", o, true));
        pending("shipping_email_sent_at", " and fulfillment_status in ('SHIPPED','DELIVERED')")
                .forEach(o -> send("shipping_email_sent_at", o, false));
    }

    private java.util.List<Map<String, Object>> pending(String column, String extra) {
        return db.queryForList("select id,order_number,email,customer_first_name,subtotal,shipping_total,total,currency,"
                + "carrier,tracking_number from orders where status='PAID' and " + column + " is null" + extra
                + " and placed_at > now() - interval '30 days' order by placed_at limit 20");
    }

    private void send(String column, Map<String, Object> o, boolean confirmation) {
        Object id = o.get("id");
        // Claim first so two instances never send the same email twice.
        if (db.update("update orders set " + column + "=now() where id=? and " + column + " is null", id) != 1) return;
        try {
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
        } catch (RuntimeException e) {
            db.update("update orders set " + column + "=null where id=?", id);
            log.warn("Order email deferred for {} ({})", id, e.getClass().getSimpleName());
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
