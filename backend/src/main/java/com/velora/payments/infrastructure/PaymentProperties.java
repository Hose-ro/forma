package com.velora.payments.infrastructure;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import com.velora.shared.domain.ConflictException;

@ConfigurationProperties("app.payments")
public record PaymentProperties(String accessToken, String webhookSecret, String environment,
        String collectorId, String frontendUrl, String backendUrl) {
    public void requireConfigured() {
        // Production is deliberately unavailable in this integration.
        if (!"test".equals(environment) || accessToken == null || accessToken.isBlank()
                || webhookSecret == null || webhookSecret.isBlank() || collectorId == null || !collectorId.matches("[0-9]+"))
            throw new ConflictException("Mercado Pago test configuration is incomplete or production is disabled");
        publicHttps(frontendUrl); publicHttps(backendUrl);
    }
    private void publicHttps(String value) {
        try {
            URI u = URI.create(value);
            if (!"https".equals(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null
                    || u.getQuery() != null || u.getFragment() != null) throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new ConflictException("Public HTTPS frontend/backend URLs are required"); }
    }
}
