package com.olvenio.aftersales.application;

import com.olvenio.orders.domain.OrderStatus;
import com.olvenio.orders.infrastructure.OrderRepository;
import com.olvenio.shared.domain.NotFoundException;
import com.olvenio.shared.security.SecretTokens;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderTracking {
    private static final Duration LINK_LIFETIME = Duration.ofDays(90);
    private final OrderRepository orders;
    private final SecretTokens tokens;
    private final JdbcTemplate db;

    public OrderTracking(OrderRepository orders, SecretTokens tokens, JdbcTemplate db) {
        this.orders = orders;
        this.tokens = tokens;
        this.db = db;
    }

    public record TrackingView(String orderNumber, String status, String fulfillmentStatus, String carrier,
                               String trackingNumber, String trackingUrl, Instant shippedAt) {}

    /** Order-specific capability for the read-only tracking projection. The fragment never reaches HTTP logs. */
    public String emailPath(UUID orderId, Instant now) {
        long expiry = now.plus(LINK_LIFETIME).getEpochSecond();
        return "/account/orders/" + orderId + "/tracking#" + expiry + "." + signature(orderId, expiry);
    }

    boolean valid(UUID orderId, String token, Instant now) {
        if (token == null || !token.matches("[0-9]{1,12}\\.[a-f0-9]{64}")) return false;
        int split = token.indexOf('.');
        long expiry = Long.parseLong(token.substring(0, split));
        return now.getEpochSecond() < expiry && expiry <= now.plus(LINK_LIFETIME).getEpochSecond()
                && tokens.matches(message(orderId, expiry), token.substring(split + 1));
    }

    private String signature(UUID orderId, long expiry) { return tokens.hash(message(orderId, expiry)); }
    private static String message(UUID orderId, long expiry) { return "order-tracking:v1:" + orderId + ":" + expiry; }

    @Transactional(readOnly = true)
    public TrackingView read(UUID id, String token) {
        if (!valid(id, token, Instant.now())) throw missing();
        var order = orders.findById(id).filter(o -> o.getStatus() != OrderStatus.DRAFT).orElseThrow(OrderTracking::missing);
        String guide = order.getTrackingNumber();
        return new TrackingView(order.getOrderNumber(), order.getStatus().name(), order.getFulfillmentStatus(),
                order.getCarrier(), guide, carrierUrl(id, order.getCarrier(), guide), order.getShippedAt());
    }

    /** Generated links are used only while the label still matches the current guide. */
    @Transactional(readOnly = true)
    public String carrierUrl(UUID orderId, String carrier, String number) {
        if (carrier == null || number == null) return null;
        return db.query("select tracking_url from shipping_labels where order_id=? and status='READY' "
                        + "and lower(carrier)=lower(?) and tracking_number=? and tracking_url is not null",
                (rs, row) -> rs.getString(1), orderId, carrier, number).stream().findFirst()
                .orElseGet(() -> manualUrl(carrier, number));
    }

    private static String manualUrl(String carrier, String guide) {
        String number = URLEncoder.encode(guide, StandardCharsets.UTF_8);
        return switch (carrier.strip().toLowerCase(Locale.ROOT)) {
            case "fedex", "fed ex" -> "https://www.fedex.com/fedextrack/?trknbr=" + number;
            case "dhl", "dhl express", "dhlexpress" -> "https://www.dhl.com/mx-es/home/rastreo.html?tracking-id=" + number;
            case "ups" -> "https://www.ups.com/track?loc=es_MX&tracknum=" + number;
            case "estafeta" -> "https://www.estafeta.com/rastrear-envio";
            default -> null;
        };
    }

    private static NotFoundException missing() { return new NotFoundException("El enlace de seguimiento no está disponible."); }
}
