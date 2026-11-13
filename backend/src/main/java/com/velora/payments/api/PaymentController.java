package com.velora.payments.api;

import com.velora.payments.application.PaymentService;
import com.velora.payments.domain.Payment;
import com.velora.payments.infrastructure.PaymentRepository;
import com.velora.shared.security.GuestSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentController {
    private final PaymentService service;
    private final PaymentRepository payments;
    private final GuestSession guest;
    public PaymentController(PaymentService service,PaymentRepository payments,GuestSession guest) {
        this.service=service;this.payments=payments;this.guest=guest;
    }
    public record PreferenceRequest(@NotNull UUID orderId) {}
    public record PreferenceView(UUID paymentId,String checkoutUrl) {}
    @PostMapping("/api/v1/payments/mercadopago/preference")
    public PreferenceView preference(@Valid @RequestBody PreferenceRequest body,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value="X-Order-Token",required=false) String token,HttpServletRequest request) {
        Payment p=service.preference(body.orderId(),key,guest.resolve(token,request));
        return new PreferenceView(p.id(),p.checkoutUrl());
    }
    @GetMapping("/api/v1/payments/orders/{orderId}")
    public Payment status(@PathVariable UUID orderId,@RequestHeader(value="X-Order-Token",required=false) String token,HttpServletRequest request) {
        return service.get(orderId,guest.resolve(token,request));
    }
    @GetMapping("/api/v1/admin/payments")
    public List<Map<String,Object>> list(@RequestParam(defaultValue="0") int page) { return payments.list(Math.min(10000,Math.max(0,page))); }
    @GetMapping("/api/v1/admin/payments/orders/{orderId}")
    public Map<String,Object> detail(@PathVariable UUID orderId) {
        Payment p=service.get(orderId,null);
        return Map.of("payment",p,"events",payments.events(p.id()));
    }
}
