package com.olvenio.payments.infrastructure.stripe;

import com.olvenio.shared.domain.ConflictException;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Stripe credentials. A secret (or restricted) key enables the gateway; its mode must match the environment. */
@ConfigurationProperties("app.payments.stripe")
public record StripeProperties(String secretKey, String webhookSecret, String environment) {
    public boolean enabled() { return secretKey != null && !secretKey.isBlank(); }
    public boolean production() { return "production".equals(environment); }
    public void requireConfigured() {
        String mode = production() ? "live" : "test";
        if (!("test".equals(environment) || production()) || !enabled()
                || !secretKey.matches("(sk|rk)_" + mode + "_[A-Za-z0-9]{10,250}")
                || webhookSecret == null || !webhookSecret.matches("whsec_[A-Za-z0-9]{10,250}"))
            throw new ConflictException("Stripe configuration is incomplete");
    }
}
