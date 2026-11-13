package com.velora.payments.domain;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Payment(UUID id, UUID orderId, String provider, String providerPaymentId,
        String providerPreferenceId, PaymentStatus status, BigDecimal amount, String currency,
        String checkoutUrl, Instant createdAt, Instant approvedAt, String failureReason) {}
