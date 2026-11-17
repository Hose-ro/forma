package com.olvenio.payments.infrastructure.conekta;

import com.fasterxml.jackson.databind.JsonNode;
import com.olvenio.payments.application.port.CheckoutBuyer;
import com.olvenio.payments.application.port.CheckoutSession;
import com.olvenio.payments.application.port.PaymentGateway;
import com.olvenio.payments.application.port.ProviderCharge;
import com.olvenio.payments.domain.Payment;
import com.olvenio.payments.domain.PaymentProvider;
import com.olvenio.payments.domain.PaymentStatus;
import com.olvenio.payments.domain.ProviderUnavailableException;
import com.olvenio.payments.infrastructure.PaymentProperties;
import com.olvenio.shared.domain.ConflictException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Conekta order with a hosted checkout (SPEI by default: a CLABE per order, reconciled automatically). The order
 * carries our payment id in metadata. Closing cancels the order; a pending transfer reference that Conekta does
 * not let us cancel stays a live charge, which keeps the stock reserved and blocks other methods until it
 * resolves.
 */
@Component
public class ConektaGateway implements PaymentGateway {
    static final Duration MIN_WINDOW = Duration.ofMinutes(10);
    private final ConektaClient client;
    private final ConektaProperties config;
    private final PaymentProperties urls;

    public ConektaGateway(ConektaClient client, ConektaProperties config, PaymentProperties urls) {
        this.client = client;
        this.config = config;
        this.urls = urls;
    }

    public PaymentProvider provider() { return PaymentProvider.CONEKTA; }
    public boolean enabled() { return config.enabled(); }
    public void requireConfigured() { config.requireConfigured(); urls.requirePublicUrls(); }
    public void requireReady() { requireConfigured(); }

    public CheckoutSession create(Payment p, Instant expiresAt) {
        throw new ConflictException("Conekta needs the buyer's contact data");
    }

    public CheckoutSession create(Payment p, Instant reservationExpiresAt, CheckoutBuyer buyer) {
        requireConfigured();
        Instant expires = reservationExpiresAt.truncatedTo(ChronoUnit.SECONDS);
        if (expires.isBefore(p.createdAt().truncatedTo(ChronoUnit.SECONDS).plus(MIN_WINDOW)))
            throw new ConflictException("The reservation for this order expires too soon to pay with this method");
        String base = urls.frontendUrl().replaceAll("/$", "");
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", buyer.name());
        customer.put("email", buyer.email());
        if (buyer.phone() != null && !buyer.phone().isBlank()) customer.put("phone", buyer.phone());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currency", "MXN");
        body.put("customer_info", customer);
        body.put("line_items", List.of(Map.of("name", "Pedido " + p.orderId().toString().substring(0, 8),
                "unit_price", cents(p.amount()), "quantity", 1)));
        body.put("metadata", Map.of("payment_id", p.id().toString(), "order_id", p.orderId().toString()));
        body.put("checkout", Map.of(
                "type", "HostedPayment",
                "allowed_payment_methods", config.allowedMethods(),
                "expires_at", expires.getEpochSecond(),
                "success_url", base + "/checkout/success?orderId=" + p.orderId(),
                "failure_url", base + "/checkout/failure?orderId=" + p.orderId()));
        return checked(client.createOrder(body, "olvenio-checkout-" + p.id()), p);
    }

    /** The creation response was lost: look for an order carrying our payment id near its creation time. */
    public Optional<CheckoutSession> findCreated(Payment p) {
        return findOrder(p).map(order -> checked(order, p));
    }

    private Optional<JsonNode> findOrder(Payment p) {
        JsonNode list = client.ordersCreatedBetween(p.createdAt().minusSeconds(120), p.createdAt().plus(Duration.ofMinutes(15)));
        if (!list.path("data").isArray()) throw new ProviderUnavailableException();
        for (JsonNode order : list.path("data"))
            if (p.id().toString().equals(order.path("metadata").path("payment_id").asText())) return Optional.of(order);
        if (list.path("data").size() >= 250) throw new ProviderUnavailableException(); // Window too busy to be sure.
        return Optional.empty();
    }

    public CheckoutSession refresh(Payment p) { return checked(client.order(p.providerPreferenceId()), p); }

    private CheckoutSession checked(JsonNode order, Payment p) {
        String id = order.path("id").asText();
        String url = order.path("checkout").path("url").asText();
        if (!id.matches("ord_[A-Za-z0-9]{1,100}") || !p.id().toString().equals(order.path("metadata").path("payment_id").asText())
                || order.path("livemode").asBoolean() != config.production() || !"MXN".equals(order.path("currency").asText())
                || !order.path("amount").isIntegralNumber() || cents(p.amount()) != order.path("amount").asLong()
                || !allowedCheckoutUrl(url)) throw new ProviderUnavailableException();
        return new CheckoutSession(id, url);
    }

    public boolean allowedCheckoutUrl(String value) {
        try {
            URI u = URI.create(value);
            // Conekta's documentation shows both hosts for the hosted checkout.
            return "https".equals(u.getScheme()) && ("pay.conekta.com".equals(u.getHost()) || "pay.conekta.io".equals(u.getHost()))
                    && u.getUserInfo() == null && u.getPort() == -1 && u.getFragment() == null;
        } catch (RuntimeException e) { return false; }
    }

    public void close(Payment p) {
        requireConfigured();
        String id = p.providerPreferenceId();
        if (id == null) {
            if (p.createdAt().isAfter(Instant.now().minusSeconds(300)))
                throw new ConflictException("Payment initialization is still in progress; retry in a few minutes");
            Optional<JsonNode> found = findOrder(p);
            if (found.isEmpty()) return;
            id = found.get().path("id").asText();
        }
        JsonNode order = client.order(id);
        if ("pending_payment".equals(order.path("payment_status").asText())) {
            client.cancelOrder(id);
            // Whatever cancel answered, the order's state now is what counts. A pending transfer that could not be
            // canceled shows up as a live charge when the charges are re-read.
            order = client.order(id);
            if ("pending_payment".equals(order.path("payment_status").asText()) && !hasLiveCharge(order))
                throw new ProviderUnavailableException();
        }
    }

    private static boolean hasLiveCharge(JsonNode order) {
        for (JsonNode charge : order.path("charges").path("data"))
            if (List.of("pending_payment", "pending_confirmation", "pre_authorized").contains(charge.path("status").asText())) return true;
        return false;
    }

    public void forEachCharge(Payment p, Instant deadline, Consumer<ProviderCharge> sink) {
        String id = p.providerPreferenceId();
        if (id == null) {
            Optional<JsonNode> found = findOrder(p);
            if (found.isEmpty()) return;
            id = found.get().path("id").asText();
        }
        if (deadline != null && Instant.now().isAfter(deadline)) throw new ProviderUnavailableException();
        Instant readAt = Instant.now();
        JsonNode order = client.order(id);
        if (!id.equals(order.path("id").asText())) throw new ProviderUnavailableException();
        for (JsonNode charge : order.path("charges").path("data")) sink.accept(toCharge(charge, order, readAt, config.production()));
    }

    /** Our payment id for a webhook object: an order directly, a charge through its order. */
    public Optional<UUID> referenceOf(JsonNode object) {
        JsonNode order = switch (object.path("object").asText()) {
            case "order" -> object;
            case "charge" -> object.path("order_id").asText().isBlank() ? null : client.order(object.path("order_id").asText());
            default -> null;
        };
        if (order == null) return Optional.empty();
        try { return Optional.of(UUID.fromString(order.path("metadata").path("payment_id").asText())); }
        catch (RuntimeException e) { return Optional.empty(); }
    }

    static long cents(BigDecimal amount) {
        try { return amount.movePointRight(2).longValueExact(); }
        catch (ArithmeticException e) { throw new ConflictException("Invalid payable amount or currency"); }
    }

    /** Charges have no update timestamp: the read instant, taken before the request, orders snapshots. */
    public static ProviderCharge toCharge(JsonNode charge, JsonNode order, Instant readAt, boolean production) {
        UUID reference;
        try { reference = UUID.fromString(order.path("metadata").path("payment_id").asText()); }
        catch (RuntimeException e) { reference = null; }
        String id = charge.path("id").asText();
        String status = charge.path("status").asText();
        BigDecimal amount = charge.path("amount").isIntegralNumber() ? BigDecimal.valueOf(charge.path("amount").asLong()).movePointLeft(2) : null;
        BigDecimal refunded = BigDecimal.ZERO;
        for (JsonNode refund : charge.path("refunds").path("data")) {
            if (!refund.path("amount").isIntegralNumber()) { refunded = null; break; }
            // Refund amounts are reported as negative cents.
            refunded = refunded.add(BigDecimal.valueOf(Math.abs(refund.path("amount").asLong())).movePointLeft(2));
        }
        PaymentStatus next = amount == null || refunded == null ? PaymentStatus.UNKNOWN : switch (status) {
            case "paid", "partially_refunded", "refunded" -> refunded.signum() > 0
                    ? (refunded.compareTo(amount) >= 0 ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED)
                    : "refunded".equals(status) ? PaymentStatus.REFUNDED : PaymentStatus.APPROVED;
            case "pending_payment", "pending_confirmation", "pre_authorized" -> PaymentStatus.PENDING;
            case "declined" -> PaymentStatus.REJECTED;
            case "canceled", "expired", "voided" -> PaymentStatus.CANCELLED;
            // Chargebacks and anything new stop fulfillment for review.
            default -> PaymentStatus.UNKNOWN;
        };
        boolean live = charge.path("livemode").asBoolean(order.path("livemode").asBoolean(false));
        boolean account = order.path("livemode").isBoolean() && order.path("livemode").asBoolean() == production && live == production;
        return new ProviderCharge(PaymentProvider.CONEKTA, reference, id.matches("[A-Za-z0-9_]{1,100}") ? id : null, status, next,
                amount, charge.path("currency").asText(order.path("currency").asText()).toUpperCase(java.util.Locale.ROOT),
                refunded, readAt, account, live, !live);
    }
}
