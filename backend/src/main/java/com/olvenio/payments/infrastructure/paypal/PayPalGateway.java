package com.olvenio.payments.infrastructure.paypal;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * PayPal Orders v2 with intent CAPTURE. The buyer only approves; the money moves when we capture, which happens
 * exclusively through {@link #complete} while the order is locked and the checkout open. Closing therefore needs
 * nothing at PayPal: an approved order nobody captures never charges the buyer.
 */
@Component
public class PayPalGateway implements PaymentGateway {
    private final PayPalClient client;
    private final PayPalProperties config;
    private final PaymentProperties urls;

    public PayPalGateway(PayPalClient client, PayPalProperties config, PaymentProperties urls) {
        this.client = client;
        this.config = config;
        this.urls = urls;
    }

    public PaymentProvider provider() { return PaymentProvider.PAYPAL; }
    public boolean enabled() { return config.enabled(); }
    public void requireConfigured() { config.requireConfigured(); urls.requirePublicUrls(); }
    public void requireReady() { requireConfigured(); }

    /** Same request id and body on a retry: PayPal returns the original order instead of a second one. */
    public CheckoutSession create(Payment p, Instant expiresAt) {
        requireConfigured();
        String base = urls.frontendUrl().replaceAll("/$", "");
        var body = Map.of(
            "intent", "CAPTURE",
            "purchase_units", List.of(Map.of(
                "reference_id", p.id().toString(),
                "custom_id", p.id().toString(),
                "invoice_id", p.id().toString(),
                "description", "Pedido " + p.orderId().toString().substring(0, 8),
                "amount", Map.of("currency_code", "MXN", "value", value(p.amount())))),
            "payment_source", Map.of("paypal", Map.of("experience_context", Map.of(
                "return_url", base + "/checkout/paypal?orderId=" + p.orderId() + "&paymentId=" + p.id(),
                "cancel_url", base + "/checkout/failure?orderId=" + p.orderId(),
                "user_action", "PAY_NOW",
                "shipping_preference", "NO_SHIPPING"))));
        return checked(client.createOrder(body, "olvenio-checkout-" + p.id()), p);
    }

    /** Recovery relies on the idempotent replay in {@link #create}. */
    public Optional<CheckoutSession> findCreated(Payment p) { return Optional.empty(); }

    public CheckoutSession refresh(Payment p) { return checked(client.order(p.providerPreferenceId()), p); }

    private CheckoutSession checked(JsonNode order, Payment p) {
        String id = order.path("id").asText();
        JsonNode unit = order.path("purchase_units").path(0);
        if (!id.matches("[A-Z0-9]{1,36}") || !p.id().toString().equals(unit.path("custom_id").asText())
                || !"MXN".equals(unit.path("amount").path("currency_code").asText())
                || !amountEquals(unit.path("amount").path("value").asText(), p.amount())) throw new ProviderUnavailableException();
        String status = order.path("status").asText();
        if ("COMPLETED".equals(status) || "VOIDED".equals(status)) throw new ConflictException("This PayPal order can no longer be paid");
        for (JsonNode link : order.path("links")) {
            String rel = link.path("rel").asText();
            if (("payer-action".equals(rel) || "approve".equals(rel)) && allowedCheckoutUrl(link.path("href").asText()))
                return new CheckoutSession(id, link.path("href").asText());
        }
        throw new ProviderUnavailableException();
    }

    public boolean allowedCheckoutUrl(String value) {
        try {
            URI u = URI.create(value);
            return "https".equals(u.getScheme()) && config.checkoutHost().equals(u.getHost())
                    && u.getUserInfo() == null && u.getPort() == -1 && u.getFragment() == null;
        } catch (RuntimeException e) { return false; }
    }

    /** Nothing to do at PayPal: only {@link #complete} can take money, and it refuses closed checkouts. */
    public void close(Payment p) {}

    public void complete(Payment p) {
        requireConfigured();
        if (p.providerPreferenceId() == null) throw new ConflictException("The PayPal order is not ready yet");
        PayPalClient.Response r = client.capture(p.providerPreferenceId(), "olvenio-capture-" + p.id());
        if (r.ok() || "ORDER_ALREADY_CAPTURED".equals(r.issue())) return;
        if ("ORDER_NOT_APPROVED".equals(r.issue()) || "PAYER_ACTION_REQUIRED".equals(r.issue()))
            throw new ConflictException("The buyer has not approved the PayPal payment yet");
        if (r.status() == 422 && List.of("INSTRUMENT_DECLINED", "TRANSACTION_REFUSED", "PAYER_CANNOT_PAY").contains(r.issue()))
            throw new ConflictException("PayPal declined the payment; try another funding source or method");
        throw new ProviderUnavailableException();
    }

    public void forEachCharge(Payment p, Instant deadline, Consumer<ProviderCharge> sink) {
        // No order id means no capture was ever requested (capture needs it), so no money can have moved.
        if (p.providerPreferenceId() == null) return;
        Instant readAt = Instant.now();
        JsonNode order = client.order(p.providerPreferenceId());
        if (!p.providerPreferenceId().equals(order.path("id").asText())) throw new ProviderUnavailableException();
        for (JsonNode unit : order.path("purchase_units")) {
            JsonNode payments = unit.path("payments");
            for (JsonNode capture : payments.path("captures"))
                sink.accept(toCharge(capture, unit, payments.path("refunds"), readAt, config.production()));
        }
    }

    /** Order id and our payment id from a webhook resource (an order or a capture). */
    public static Optional<UUID> referenceOf(JsonNode resource) {
        String value = resource.path("custom_id").asText();
        if (value.isBlank()) value = resource.path("purchase_units").path(0).path("custom_id").asText();
        try { return Optional.of(UUID.fromString(value)); }
        catch (RuntimeException e) { return Optional.empty(); }
    }

    static String value(BigDecimal amount) {
        try { return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(); }
        catch (ArithmeticException e) { throw new ConflictException("Invalid payable amount or currency"); }
    }
    private static boolean amountEquals(String value, BigDecimal amount) {
        try { return new BigDecimal(value).compareTo(amount) == 0; }
        catch (RuntimeException e) { return false; }
    }

    public static ProviderCharge toCharge(JsonNode capture, JsonNode unit, JsonNode refunds, Instant readAt, boolean production) {
        UUID reference;
        String custom = capture.path("custom_id").asText();
        try { reference = UUID.fromString(custom.isBlank() ? unit.path("custom_id").asText() : custom); }
        catch (RuntimeException e) { reference = null; }
        String id = capture.path("id").asText();
        String status = capture.path("status").asText();
        BigDecimal amount;
        try { amount = new BigDecimal(capture.path("amount").path("value").asText()); }
        catch (RuntimeException e) { amount = null; }
        // intent=CAPTURE allows a single capture per order: every refund of the unit belongs to it.
        BigDecimal refunded = BigDecimal.ZERO;
        try {
            for (JsonNode refund : refunds)
                if ("COMPLETED".equals(refund.path("status").asText()))
                    refunded = refunded.add(new BigDecimal(refund.path("amount").path("value").asText()));
        } catch (RuntimeException e) { refunded = null; }
        if (refunded != null && amount != null && "REFUNDED".equals(status) && refunded.signum() == 0) refunded = amount;
        Instant updated;
        try { updated = OffsetDateTime.parse(capture.path("update_time").asText()).toInstant(); }
        catch (RuntimeException e) { updated = readAt; }
        PaymentStatus next = amount == null || refunded == null ? PaymentStatus.UNKNOWN : switch (status) {
            case "COMPLETED", "PARTIALLY_REFUNDED", "REFUNDED" -> refunded.signum() > 0
                    ? (refunded.compareTo(amount) >= 0 ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED) : PaymentStatus.APPROVED;
            case "PENDING" -> PaymentStatus.PENDING;
            case "DECLINED", "FAILED" -> PaymentStatus.REJECTED;
            // Reversals (chargebacks) and anything new stop fulfillment for review.
            default -> PaymentStatus.UNKNOWN;
        };
        // The order was read with our own credentials from the configured environment: PayPal does not expose
        // another merchant's orders, and sandbox and live are separate hosts.
        return new ProviderCharge(PaymentProvider.PAYPAL, reference, id.matches("[A-Z0-9]{1,36}") ? id : null, status, next,
                amount, capture.path("amount").path("currency_code").asText(), refunded, updated, true, production, !production);
    }
}
