package com.velora.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable non-negative monetary amount in an ISO 4217 currency. Never uses floating point. */
public record Money(BigDecimal amount, String currency) {

    private static final Pattern ISO_4217 = Pattern.compile("^[A-Z]{3}$");
    public static final int DISPLAY_SCALE = 2;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (!ISO_4217.matcher(currency).matches()) {
            throw new IllegalArgumentException("Invalid ISO 4217 currency: " + currency);
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Money cannot be negative");
        }
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money times(int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity cannot be negative");
        }
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
    }

    /** Amount rounded for presentation/persistence of totals (banker's rounding avoids bias). */
    public BigDecimal rounded() {
        return amount.setScale(DISPLAY_SCALE, RoundingMode.HALF_EVEN);
    }

    public boolean sameAmountAs(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) == 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    public static BigDecimal display(BigDecimal value) {
        return value == null ? null : value.setScale(DISPLAY_SCALE, RoundingMode.HALF_EVEN);
    }
}
