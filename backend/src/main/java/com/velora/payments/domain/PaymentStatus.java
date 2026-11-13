package com.velora.payments.domain;

import java.math.BigDecimal;
import java.time.Instant;

public enum PaymentStatus {
    CREATED, PENDING, IN_PROCESS, APPROVED, REJECTED, CANCELLED, REFUNDED, PARTIALLY_REFUNDED, UNKNOWN;

    public static PaymentStatus fromProvider(String status, BigDecimal refunded, BigDecimal amount) {
        return switch (status) {
            case "approved" -> refunded.signum() > 0 ? (refunded.compareTo(amount) >= 0 ? REFUNDED : PARTIALLY_REFUNDED) : APPROVED;
            case "pending", "authorized" -> PENDING;
            case "in_process" -> IN_PROCESS;
            case "rejected" -> REJECTED;
            case "cancelled" -> CANCELLED;
            case "refunded" -> REFUNDED;
            // Disputes and chargebacks must stop fulfillment and go to review, never masquerade as refunds.
            case "in_mediation", "charged_back" -> UNKNOWN;
            default -> UNKNOWN;
        };
    }

    public static boolean accepts(PaymentStatus before, Instant previous, PaymentStatus after, Instant incoming) {
        if (!incoming.isAfter(previous)) return false;
        if (before == REFUNDED) return after == REFUNDED || after == UNKNOWN;
        if (before == PARTIALLY_REFUNDED) return after == PARTIALLY_REFUNDED || after == REFUNDED || after == UNKNOWN;
        if (before == APPROVED) return after == APPROVED || after == PARTIALLY_REFUNDED || after == REFUNDED || after == UNKNOWN;
        return true;
    }
}
