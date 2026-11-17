package com.olvenio.payments.api;

import com.olvenio.payments.application.PayPalWebhookService;
import org.springframework.web.bind.annotation.*;

@RestController
public class PayPalWebhookController {
    private final PayPalWebhookService service;
    public PayPalWebhookController(PayPalWebhookService service) { this.service = service; }

    @PostMapping(value = "/api/v1/webhooks/paypal", consumes = "application/json")
    public void receive(@RequestHeader(value = "PAYPAL-AUTH-ALGO", required = false) String algo,
                        @RequestHeader(value = "PAYPAL-CERT-URL", required = false) String certUrl,
                        @RequestHeader(value = "PAYPAL-TRANSMISSION-ID", required = false) String id,
                        @RequestHeader(value = "PAYPAL-TRANSMISSION-SIG", required = false) String signature,
                        @RequestHeader(value = "PAYPAL-TRANSMISSION-TIME", required = false) String time,
                        @RequestBody byte[] body) {
        service.receive(new PayPalWebhookService.Transmission(algo, certUrl, id, signature, time), body);
    }
}
