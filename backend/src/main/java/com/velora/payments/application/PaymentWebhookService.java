package com.velora.payments.application;

import com.velora.payments.infrastructure.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class PaymentWebhookService {
    private final PaymentProperties config;
    private final MercadoPagoClient client;
    private final PaymentVerificationService verification;
    public PaymentWebhookService(PaymentProperties config,MercadoPagoClient client,PaymentVerificationService verification) {
        this.config=config;this.client=client;this.verification=verification;
    }
    public static class InvalidSignatureException extends com.velora.shared.domain.DomainException {
        public InvalidSignatureException() { super("Invalid webhook signature"); }
        public int status() { return 401; }
        public String title() { return "Unauthorized"; }
    }
    public void receive(String signature,String requestId,String dataId,String type) {
        if (!WebhookSignature.valid(signature,requestId,dataId,config.webhookSecret())) throw new InvalidSignatureException();
        // The signature authenticates the resource id, not arbitrary notification body fields or event ids.
        // Fetch every delivery; deduplicate by the official resource version inside the database transaction.
        if (!"payment".equals(type)) return;
        var resource=client.payment(dataId);
        if (!dataId.equals(resource.path("id").asText())) throw new MercadoPagoClient.ProviderException();
        verification.apply(resource);
    }
}
