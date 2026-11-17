package com.olvenio.payments.infrastructure.stripe;

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
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Stripe Checkout (cards and OXXO). One Checkout Session per payment; its PaymentIntent is created lazily when the
 * buyer submits the form. Our payment id travels as client_reference_id and as PaymentIntent metadata.
 */
@Component
public class StripeGateway implements PaymentGateway {
    /** Stripe accepts session expiry 30 min to 24 h after creation; keep a margin on both ends. */
    static final Duration MIN_WINDOW = Duration.ofMinutes(32);
    static final Duration MAX_WINDOW = Duration.ofHours(23).plusMinutes(50);
    private final StripeClient client;
    private final StripeProperties config;
    private final PaymentProperties urls;

    public StripeGateway(StripeClient client, StripeProperties config, PaymentProperties urls) {
        this.client = client;
        this.config = config;
        this.urls = urls;
    }

    public PaymentProvider provider() { return PaymentProvider.STRIPE; }
    public boolean enabled() { return config.enabled(); }
    public void requireConfigured() { config.requireConfigured(); urls.requirePublicUrls(); }
    public void requireReady() { requireConfigured(); }

    /**
     * Deterministic for a given payment and reservation: a retry after an uncertain result sends the same
     * parameters under the same idempotency key, and Stripe replays the original session instead of creating one.
     */
    public CheckoutSession create(Payment p, Instant reservationExpiresAt) {
        requireConfigured();
        Instant expires = expiresAt(p, reservationExpiresAt);
        String base = urls.frontendUrl().replaceAll("/$", "");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("mode", "payment");
        form.put("client_reference_id", p.id().toString());
        form.put("metadata[payment_id]", p.id().toString());
        form.put("metadata[order_id]", p.orderId().toString());
        form.put("payment_intent_data[metadata][payment_id]", p.id().toString());
        form.put("payment_intent_data[metadata][order_id]", p.orderId().toString());
        form.put("line_items[0][quantity]", "1");
        form.put("line_items[0][price_data][currency]", "mxn");
        form.put("line_items[0][price_data][unit_amount]", cents(p.amount()).toString());
        form.put("line_items[0][price_data][product_data][name]", "Pedido " + p.orderId().toString().substring(0, 8));
        form.put("payment_method_types[0]", "card");
        form.put("payment_method_types[1]", "oxxo");
        // An OXXO voucher is payable for days after the session closes: keep it as short as Stripe allows.
        form.put("payment_method_options[oxxo][expires_after_days]", "1");
        form.put("expires_at", String.valueOf(expires.getEpochSecond()));
        form.put("success_url", base + "/checkout/success?orderId=" + p.orderId());
        form.put("cancel_url", base + "/checkout/failure?orderId=" + p.orderId());
        JsonNode session = client.createSession(form, "olvenio-checkout-" + p.id());
        return checked(session, p);
    }

    static Instant expiresAt(Payment p, Instant reservationExpiresAt) {
        Instant created = p.createdAt().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = reservationExpiresAt.truncatedTo(ChronoUnit.SECONDS);
        if (expires.isAfter(created.plus(MAX_WINDOW))) expires = created.plus(MAX_WINDOW);
        if (expires.isBefore(created.plus(MIN_WINDOW)))
            throw new ConflictException("The reservation for this order expires too soon to pay with this method");
        return expires;
    }

    /** Recovery relies on the idempotent replay in {@link #create}: there is nothing to look up first. */
    public Optional<CheckoutSession> findCreated(Payment p) { return Optional.empty(); }

    public CheckoutSession refresh(Payment p) { return checked(client.session(p.providerPreferenceId()), p); }

    private CheckoutSession checked(JsonNode session, Payment p) {
        String id = session.path("id").asText();
        String url = session.path("url").asText();
        if (!id.startsWith("cs_") || !p.id().toString().equals(session.path("client_reference_id").asText())
                || session.path("livemode").asBoolean() != config.production()
                || !"mxn".equals(session.path("currency").asText())
                || !session.path("amount_total").isIntegralNumber()
                || !cents(p.amount()).equals(session.path("amount_total").bigIntegerValue().longValue())
                || !allowedCheckoutUrl(url)) throw new ProviderUnavailableException();
        return new CheckoutSession(id, url);
    }

    public boolean allowedCheckoutUrl(String value) {
        try {
            URI u = URI.create(value);
            // Session URLs carry a client-side fragment; the host is what must be trusted.
            return "https".equals(u.getScheme()) && "checkout.stripe.com".equals(u.getHost())
                    && u.getUserInfo() == null && u.getPort() == -1 && u.getPath() != null && u.getPath().startsWith("/");
        } catch (RuntimeException e) { return false; }
    }

    public void close(Payment p) {
        requireConfigured();
        if (p.providerPreferenceId() == null) {
            // The session id was never learned. It may exist and stay payable until Stripe's 24 h maximum.
            if (p.createdAt().isAfter(Instant.now().minus(Duration.ofHours(24).plusMinutes(5))))
                throw new ConflictException("Payment initialization is still in progress; retry in a few minutes");
            return;
        }
        JsonNode session = client.session(p.providerPreferenceId());
        if ("open".equals(session.path("status").asText())) {
            client.expireSession(session.path("id").asText());
            session = client.session(p.providerPreferenceId());
            if ("open".equals(session.path("status").asText())) throw new ProviderUnavailableException();
        }
        // A displayed OXXO voucher survives the session. Cancel it when Stripe allows; otherwise the intent stays
        // live (PENDING) and keeps the order's stock and blocks other methods until the voucher expires or is paid.
        JsonNode intent = session.path("payment_intent");
        String status = intent.path("status").asText();
        if (intent.isObject() && (status.startsWith("requires_") && !"requires_capture".equals(status)))
            client.cancelPaymentIntent(intent.path("id").asText());
    }

    public void forEachCharge(Payment p, Instant deadline, Consumer<ProviderCharge> sink) {
        Set<String> intents = new LinkedHashSet<>();
        if (p.providerPreferenceId() != null) {
            JsonNode intent = client.session(p.providerPreferenceId()).path("payment_intent");
            if (intent.isObject()) intents.add(intent.path("id").asText());
            else if (intent.isTextual()) intents.add(intent.asText());
        }
        String page = null;
        for (int i = 0; i < 5; i++) {
            requireBefore(deadline);
            JsonNode result = client.searchPaymentIntents(p.id(), page);
            if (!result.path("data").isArray()) throw new ProviderUnavailableException();
            result.path("data").forEach(pi -> intents.add(pi.path("id").asText()));
            if (!result.path("has_more").asBoolean() || result.path("next_page").asText().isBlank()) break;
            page = result.path("next_page").asText();
        }
        for (String id : intents) {
            requireBefore(deadline);
            Instant readAt = Instant.now();
            sink.accept(toCharge(client.paymentIntent(id), readAt, config.production()));
        }
    }

    /** Resolves a webhook object to the local payment it belongs to, reading the intent when needed. */
    public Optional<UUID> referenceOf(JsonNode object) {
        String type = object.path("object").asText();
        String value = switch (type) {
            case "checkout.session" -> object.path("client_reference_id").asText();
            case "payment_intent" -> object.path("metadata").path("payment_id").asText();
            case "charge", "dispute" -> {
                String pi = object.path("payment_intent").asText();
                yield pi.isBlank() ? "" : client.paymentIntent(pi).path("metadata").path("payment_id").asText();
            }
            default -> "";
        };
        try { return Optional.of(UUID.fromString(value)); }
        catch (RuntimeException e) { return Optional.empty(); }
    }

    private static void requireBefore(Instant deadline) {
        if (deadline != null && Instant.now().isAfter(deadline)) throw new ProviderUnavailableException();
    }

    static Long cents(BigDecimal amount) {
        try { return amount.movePointRight(2).longValueExact(); }
        catch (ArithmeticException e) { throw new ConflictException("Invalid payable amount or currency"); }
    }

    /**
     * PaymentIntents carry no update timestamp: the read instant, taken before the request, orders snapshots
     * (a read that starts later sees a state at least as new).
     */
    public static ProviderCharge toCharge(JsonNode pi, Instant readAt, boolean production) {
        UUID reference;
        try { reference = UUID.fromString(pi.path("metadata").path("payment_id").asText()); }
        catch (RuntimeException e) { reference = null; }
        String id = pi.path("id").asText();
        String status = pi.path("status").asText();
        BigDecimal amount = pi.path("amount").isIntegralNumber() ? new BigDecimal(pi.path("amount").bigIntegerValue()).movePointLeft(2) : null;
        JsonNode charge = pi.path("latest_charge");
        BigDecimal refunded = BigDecimal.ZERO;
        if (charge.isObject()) refunded = charge.path("amount_refunded").isIntegralNumber()
                ? new BigDecimal(charge.path("amount_refunded").bigIntegerValue()).movePointLeft(2) : null;
        else if (!charge.isMissingNode() && !charge.isNull()) refunded = null; // Not expanded: cannot judge refunds.
        boolean live = pi.path("livemode").asBoolean(false);
        PaymentStatus next = amount == null || refunded == null ? PaymentStatus.UNKNOWN : switch (status) {
            case "succeeded" -> charge.path("disputed").asBoolean(false) ? PaymentStatus.UNKNOWN
                    : refunded.signum() > 0 ? (refunded.compareTo(amount) >= 0 ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED)
                    : PaymentStatus.APPROVED;
            case "processing" -> PaymentStatus.IN_PROCESS;
            // OXXO voucher displayed and unpaid, or an authorization awaiting capture.
            case "requires_action", "requires_capture" -> PaymentStatus.PENDING;
            // No money moved: a declined card, an expired voucher, or a form never submitted.
            case "requires_payment_method", "requires_confirmation" -> PaymentStatus.REJECTED;
            case "canceled" -> PaymentStatus.CANCELLED;
            default -> PaymentStatus.UNKNOWN;
        };
        return new ProviderCharge(PaymentProvider.STRIPE, reference, id.matches("pi_[A-Za-z0-9]{1,96}") ? id : null, status,
                next, amount, pi.path("currency").asText().toUpperCase(java.util.Locale.ROOT), refunded, readAt,
                pi.path("livemode").isBoolean() && live == production, live, !live);
    }
}
