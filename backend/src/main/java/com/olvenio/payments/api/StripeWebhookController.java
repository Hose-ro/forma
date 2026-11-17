package com.olvenio.payments.api;

import com.olvenio.payments.application.StripeWebhookService;
import org.springframework.web.bind.annotation.*;

@RestController
public class StripeWebhookController {
    private final StripeWebhookService service;
    public StripeWebhookController(StripeWebhookService service) { this.service = service; }

    /** The raw bytes are signed: never let a converter re-serialize them before verification. */
    @PostMapping(value = "/api/v1/webhooks/stripe", consumes = "application/json")
    public void receive(@RequestHeader(value = "Stripe-Signature", required = false) String signature,
                        @RequestBody byte[] body) {
        service.receive(signature, body);
    }
}
