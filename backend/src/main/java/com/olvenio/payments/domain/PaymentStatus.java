package com.olvenio.payments.domain;

import java.time.Instant;

public enum PaymentStatus {
    CREATED, PENDING, IN_PROCESS, APPROVED, REJECTED, CANCELLED, REFUNDED, PARTIALLY_REFUNDED, UNKNOWN;

    public static boolean accepts(PaymentStatus before, Instant previous, PaymentStatus after, Instant incoming) {
        if (!incoming.isAfter(previous)) return false;
        if (before == REFUNDED) return after == REFUNDED || after == UNKNOWN;
        if (before == PARTIALLY_REFUNDED) return after == PARTIALLY_REFUNDED || after == REFUNDED || after == UNKNOWN;
        if (before == APPROVED) return after == APPROVED || after == PARTIALLY_REFUNDED || after == REFUNDED || after == UNKNOWN;
        return true;
    }
}
