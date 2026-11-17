package com.olvenio.payments.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olvenio.payments.domain.PaymentProvider;
import com.olvenio.payments.infrastructure.PaymentRepository;
import com.olvenio.payments.infrastructure.paypal.PayPalClient;
import com.olvenio.payments.infrastructure.paypal.PayPalGateway;
import com.olvenio.payments.infrastructure.paypal.PayPalProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * PayPal events, authenticated by PayPal's verification API. The event only says which payment to look at:
 * captures are re-read from the API, and an approval triggers the same locked capture as the buyer's return.
 */
@Service
public class PayPalWebhookService {
    public record Transmission(String authAlgo, String certUrl, String id, String signature, String time) {
        boolean complete() {
            for (String v : new String[]{authAlgo, certUrl, id, signature, time})
                if (v == null || v.isBlank() || v.length() > 2048) return false;
            return true;
        }
    }
    private final PayPalProperties config;
    private final PayPalClient client;
    private final PayPalGateway gateway;
    private final PaymentRepository payments;
    private final PaymentService service;
    private final PaymentVerificationService verification;
    private final ObjectMapper json;

    public PayPalWebhookService(PayPalProperties config, PayPalClient client, PayPalGateway gateway, PaymentRepository payments,
                                PaymentService service, PaymentVerificationService verification, ObjectMapper json) {
        this.config = config;
        this.client = client;
        this.gateway = gateway;
        this.payments = payments;
        this.service = service;
        this.verification = verification;
        this.json = json;
    }

    public void receive(Transmission transmission, byte[] body) {
        if (!config.enabled() || !transmission.complete()) throw new PaymentWebhookService.InvalidSignatureException();
        JsonNode event;
        try { event = json.readTree(body); }
        catch (Exception e) { throw new PaymentWebhookService.InvalidSignatureException(); }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("auth_algo", transmission.authAlgo());
        request.put("cert_url", transmission.certUrl());
        request.put("transmission_id", transmission.id());
        request.put("transmission_sig", transmission.signature());
        request.put("transmission_time", transmission.time());
        request.put("webhook_id", config.webhookId());
        request.put("webhook_event", event);
        if (!"SUCCESS".equals(client.verifyWebhook(request).path("verification_status").asText()))
            throw new PaymentWebhookService.InvalidSignatureException();
        String type = event.path("event_type").asText();
        JsonNode resource = event.path("resource");
        var payment = PayPalGateway.referenceOf(resource).flatMap(payments::find)
                .filter(p -> PaymentProvider.PAYPAL.name().equals(p.provider()));
        if (payment.isEmpty()) return;
        if ("CHECKOUT.ORDER.APPROVED".equals(type)) {
            if (resource.path("id").asText().equals(payment.get().providerPreferenceId())) service.confirmFromProvider(payment.get().id());
        } else if (type.startsWith("PAYMENT.CAPTURE.")) {
            gateway.forEachCharge(payment.get(), null, verification::apply);
        }
    }
}
