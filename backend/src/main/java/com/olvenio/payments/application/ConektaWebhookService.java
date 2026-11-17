package com.olvenio.payments.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olvenio.payments.domain.PaymentProvider;
import com.olvenio.payments.infrastructure.PaymentRepository;
import com.olvenio.payments.infrastructure.conekta.ConektaGateway;
import com.olvenio.payments.infrastructure.conekta.ConektaProperties;
import com.olvenio.payments.infrastructure.conekta.ConektaSignature;
import org.springframework.stereotype.Service;

/** Conekta events point at a payment; its charges are always re-read from the API before anything changes. */
@Service
public class ConektaWebhookService {
    private final ConektaProperties config;
    private final ConektaGateway gateway;
    private final PaymentRepository payments;
    private final PaymentVerificationService verification;
    private final ObjectMapper json;

    public ConektaWebhookService(ConektaProperties config, ConektaGateway gateway, PaymentRepository payments,
                                 PaymentVerificationService verification, ObjectMapper json) {
        this.config = config;
        this.gateway = gateway;
        this.payments = payments;
        this.verification = verification;
        this.json = json;
    }

    public void receive(String digest, byte[] body) {
        if (!config.enabled() || !ConektaSignature.valid(digest, body, config.publicKey()))
            throw new PaymentWebhookService.InvalidSignatureException();
        JsonNode event;
        try { event = json.readTree(body); }
        catch (Exception e) { return; }
        String type = event.path("type").asText();
        if (!(type.startsWith("order.") || type.startsWith("charge."))) return;
        if (event.path("livemode").asBoolean() != config.production()) return;
        gateway.referenceOf(event.path("data").path("object"))
                .flatMap(payments::find)
                .filter(p -> PaymentProvider.CONEKTA.name().equals(p.provider()))
                .ifPresent(p -> gateway.forEachCharge(p, null, verification::apply));
    }
}
