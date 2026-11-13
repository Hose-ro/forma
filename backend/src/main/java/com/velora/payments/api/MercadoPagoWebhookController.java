package com.velora.payments.api;

import com.velora.payments.application.PaymentWebhookService;
import org.springframework.web.bind.annotation.*;

@RestController
public class MercadoPagoWebhookController {
    private final PaymentWebhookService service;
    public MercadoPagoWebhookController(PaymentWebhookService service) { this.service=service; }
    @PostMapping("/api/v1/webhooks/mercadopago")
    public void receive(@RequestHeader(value="x-signature",required=false) String signature,
            @RequestHeader(value="x-request-id",required=false) String requestId,
            @RequestParam(value="data.id",required=false) String dataId,
            @RequestParam(required=false) String type,
            @RequestBody(required=false) com.fasterxml.jackson.databind.JsonNode body) {
        service.receive(signature,requestId,dataId,type!=null?type:(body==null?null:body.path("type").asText()));
    }
}
