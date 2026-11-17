package com.olvenio.payments.application;

import com.olvenio.payments.infrastructure.*;
import org.springframework.stereotype.Service;

@Service
public class PaymentWebhookService {
    private final PaymentProperties config;
    private final MercadoPagoGateway mercadoPago;
    private final PaymentVerificationService verification;
    public PaymentWebhookService(PaymentProperties config,MercadoPagoGateway mercadoPago,PaymentVerificationService verification) {
        this.config=config;this.mercadoPago=mercadoPago;this.verification=verification;
    }
    public static class InvalidSignatureException extends com.olvenio.shared.domain.DomainException {
        public InvalidSignatureException() { super("Invalid webhook signature"); }
        public int status() { return 401; }
        public String title() { return "Unauthorized"; }
    }
    /** Mercado Pago notification. */
    public void receive(String signature,String requestId,String dataId,String type) {
        if (!WebhookSignature.valid(signature,requestId,dataId,config.webhookSecret())) throw new InvalidSignatureException();
        // The signature authenticates the resource id, not arbitrary notification body fields or event ids.
        // Fetch every delivery; deduplicate by the official resource version inside the database transaction.
        if (!"payment".equals(type)) return;
        verification.apply(mercadoPago.webhookCharge(dataId));
    }
}
