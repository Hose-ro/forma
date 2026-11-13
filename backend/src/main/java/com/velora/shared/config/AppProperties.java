package com.velora.shared.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String frontendOrigin, String sessionSecret, boolean cookieSecure, String currency,
                            Checkout checkout, Admin admin) {

    public AppProperties {
        currency = currency == null ? "MXN" : currency;
        checkout = checkout == null ? new Checkout(null, null) : checkout;
        admin = admin == null ? new Admin(null, null) : admin;
    }

    public List<String> allowedOrigins() {
        List<String> origins = frontendOrigin == null ? List.of()
                : Arrays.stream(frontendOrigin.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        for (String origin : origins) {
            var uri = java.net.URI.create(origin);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !uri.getPath().isEmpty() || origin.contains("*"))
                throw new IllegalStateException("APP_FRONTEND_ORIGIN must contain exact HTTP(S) origins");
        }
        return origins;
    }

    public record Checkout(Duration quoteTtl, BigDecimal shippingFlat) {
        public Checkout {
            quoteTtl = quoteTtl == null ? Duration.ofMinutes(30) : quoteTtl;
            shippingFlat = shippingFlat == null ? BigDecimal.ZERO : shippingFlat;
        }
    }

    public record Admin(String email, String password) {
    }
}
