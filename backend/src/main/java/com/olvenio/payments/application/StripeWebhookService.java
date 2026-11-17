package com.olvenio.payments.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olvenio.payments.domain.PaymentProvider;
import com.olvenio.payments.infrastructure.PaymentRepository;
import com.olvenio.payments.infrastructure.stripe.StripeGateway;
import com.olvenio.payments.infrastructure.stripe.StripeProperties;
import com.olvenio.payments.infrastructure.stripe.StripeSignature;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Stripe events only tell us which payment to look at. The state applied always comes from fresh API reads, so
 * events may arrive late, twice or out of order.
 */
@Service
public class StripeWebhookService {
    static final Set<String> RELEVANT = Set.of("checkout.session.completed", "checkout.session.async_payment_succeeded",
            "checkout.session.async_payment_failed", "checkout.session.expired", "payment_intent.succeeded",
            "payment_intent.payment_failed", "payment_intent.canceled", "payment_intent.processing",
            "charge.refunded", "charge.dispute.created", "charge.dispute.closed");
    private final StripeProperties config;
    private final StripeGateway gateway;
    private final PaymentRepository payments;
    private final PaymentVerificationService verification;
    private final ObjectMapper json;

    public StripeWebhookService(StripeProperties config, StripeGateway gateway, PaymentRepository payments,
                                PaymentVerificationService verification, ObjectMapper json) {
        this.config = config;
        this.gateway = gateway;
        this.payments = payments;
        this.verification = verification;
        this.json = json;
    }

    public void receive(String signature, byte[] body) {
        if (!config.enabled() || !StripeSignature.valid(signature, body, config.webhookSecret(), Instant.now()))
            throw new PaymentWebhookService.InvalidSignatureException();
        JsonNode event;
        try { event = json.readTree(body); }
        catch (Exception e) { return; }
        if (!RELEVANT.contains(event.path("type").asText()) || event.path("livemode").asBoolean() != config.production()) return;
        gateway.referenceOf(event.path("data").path("object"))
                .flatMap(payments::find)
                .filter(p -> PaymentProvider.STRIPE.name().equals(p.provider()))
                .ifPresent(p -> gateway.forEachCharge(p, null, verification::apply));
    }
}
