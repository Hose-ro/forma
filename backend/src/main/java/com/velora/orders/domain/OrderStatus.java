package com.velora.orders.domain;

/** Financial states are changed only by the verified payment integration. */
public enum OrderStatus {
    DRAFT, PENDING_PAYMENT, PAID, PAYMENT_REVIEW, REFUNDED, PARTIALLY_REFUNDED, CANCELLED
}
