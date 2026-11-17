package com.olvenio.payments.api;

import com.olvenio.payments.application.ConektaWebhookService;
import org.springframework.web.bind.annotation.*;

@RestController
public class ConektaWebhookController {
    private final ConektaWebhookService service;
    public ConektaWebhookController(ConektaWebhookService service) { this.service = service; }

    /** The DIGEST header signs the raw bytes; they reach verification untouched. */
    @PostMapping(value = "/api/v1/webhooks/conekta", consumes = "application/json")
    public void receive(@RequestHeader(value = "DIGEST", required = false) String digest, @RequestBody byte[] body) {
        service.receive(digest, body);
    }
}
