package com.olvenio.payments.infrastructure.paypal;

import com.olvenio.shared.domain.ConflictException;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** PayPal REST app credentials. test = sandbox app, production = live app. */
@ConfigurationProperties("app.payments.paypal")
public record PayPalProperties(String clientId, String clientSecret, String webhookId, String environment) {
    public boolean enabled() { return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank(); }
    public boolean production() { return "production".equals(environment); }
    public String apiBase() { return production() ? "https://api-m.paypal.com" : "https://api-m.sandbox.paypal.com"; }
    public String checkoutHost() { return production() ? "www.paypal.com" : "www.sandbox.paypal.com"; }
    public void requireConfigured() {
        if (!("test".equals(environment) || production()) || !enabled()
                || webhookId == null || !webhookId.matches("[A-Z0-9]{5,64}"))
            throw new ConflictException("PayPal configuration is incomplete");
    }
}
