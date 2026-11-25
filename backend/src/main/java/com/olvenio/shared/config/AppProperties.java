package com.olvenio.shared.config;

import com.olvenio.shared.address.PostalDirectory;
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
        checkout = checkout == null ? new Checkout(null, null, false, null) : checkout;
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

    /** Shipping is free on every order; these rules only say where the store delivers and how long it takes. */
    public record Checkout(Duration quoteTtl, String shippingZones, boolean shippingOnlyZones, String deliveryEstimate) {
        public Checkout {
            quoteTtl = quoteTtl == null ? Duration.ofMinutes(30) : quoteTtl;
            deliveryEstimate = deliveryEstimate == null || deliveryEstimate.isBlank() ? "3 a 7 días hábiles" : deliveryEstimate;
        }

        /** Listed states in declaration order. A rate left over from paid shipping ("Jalisco=149") is ignored. */
        public java.util.List<String> zones() {
            if (shippingZones == null) return java.util.List.of();
            return java.util.Arrays.stream(shippingZones.split("[;,]"))
                    .map(entry -> entry.split("=", 2)[0].trim()).filter(state -> !state.isEmpty()).toList();
        }

        /** Whether the store delivers to a destination state. */
        public boolean deliversTo(String state) {
            String destination = PostalDirectory.canonicalState(state);
            return !shippingOnlyZones || zones().stream()
                    .anyMatch(zone -> PostalDirectory.same(PostalDirectory.canonicalState(zone), destination));
        }
    }

    public record Admin(String email, String password) {
    }
}
