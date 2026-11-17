package com.olvenio.payments.api;

import com.olvenio.payments.application.PaymentGateways;
import com.olvenio.payments.application.PaymentService;
import com.olvenio.payments.domain.Payment;
import com.olvenio.payments.domain.PaymentProvider;
import com.olvenio.payments.infrastructure.PaymentRepository;
import com.olvenio.shared.security.GuestSession;
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
    private final PaymentGateways gateways;
    public PaymentController(PaymentService service,PaymentRepository payments,GuestSession guest,PaymentGateways gateways) {
        this.service=service;this.payments=payments;this.guest=guest;this.gateways=gateways;
    }
    public record PreferenceRequest(@NotNull UUID orderId) {}
    public record PreferenceView(UUID paymentId,String checkoutUrl) {}
    public record CheckoutRequest(@NotNull UUID orderId,@NotNull PaymentProvider provider) {}
    public record CheckoutView(UUID paymentId,PaymentProvider provider,String checkoutUrl) {}
    public record MethodView(PaymentProvider provider) {}
    /** Methods a buyer can choose now: providers whose credentials are configured. */
    @GetMapping("/api/v1/payments/methods")
    public List<MethodView> methods() {
        return gateways.enabled().stream().map(g->new MethodView(g.provider())).sorted(Comparator.comparing(MethodView::provider)).toList();
    }
    /** Opens (or reuses) the checkout of the chosen method. Choosing another method closes the previous one. */
    @PostMapping("/api/v1/payments/checkout")
    public CheckoutView checkout(@Valid @RequestBody CheckoutRequest body,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value="X-Order-Token",required=false) String token,HttpServletRequest request) {
        Payment p=service.checkout(body.orderId(),body.provider(),key,guest.resolve(token,request));
        return new CheckoutView(p.id(),PaymentProvider.valueOf(p.provider()),p.checkoutUrl());
    }
    @PostMapping("/api/v1/payments/mercadopago/preference")
    public PreferenceView preference(@Valid @RequestBody PreferenceRequest body,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value="X-Order-Token",required=false) String token,HttpServletRequest request) {
        Payment p=service.preference(body.orderId(),key,guest.resolve(token,request));
        return new PreferenceView(p.id(),p.checkoutUrl());
    }
    /** Return page of a hosted checkout. For PayPal this is where the approved payment is captured. */
    @PostMapping("/api/v1/payments/{paymentId}/confirm")
    public Payment confirm(@PathVariable UUID paymentId,@RequestHeader(value="X-Order-Token",required=false) String token,HttpServletRequest request) {
        return service.confirm(paymentId,guest.resolve(token,request));
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
        return Map.of("payment",p,"events",payments.orderEvents(orderId),"attempts",payments.attempts(orderId));
    }
}
