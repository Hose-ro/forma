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
        checkout = checkout == null ? new Checkout(null, null, null, null, false, null) : checkout;
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

    /** Shipping rules: per-state rates ("Jalisco=149;Nuevo León=129"), a flat fallback and an optional free-shipping threshold. */
    public record Checkout(Duration quoteTtl, BigDecimal shippingFlat, BigDecimal shippingFreeFrom, String shippingZones,
                           boolean shippingOnlyZones, String deliveryEstimate) {
        public Checkout {
            quoteTtl = quoteTtl == null ? Duration.ofMinutes(30) : quoteTtl;
            shippingFlat = shippingFlat == null ? BigDecimal.ZERO : shippingFlat;
            deliveryEstimate = deliveryEstimate == null || deliveryEstimate.isBlank() ? "3 a 7 días hábiles" : deliveryEstimate;
            if (shippingFlat.signum() < 0 || (shippingFreeFrom != null && shippingFreeFrom.signum() < 0))
                throw new IllegalStateException("Shipping amounts cannot be negative");
            parseZones(shippingZones); // fail at startup on a malformed value
        }

        /** Display name -> rate, in declaration order. */
        public java.util.Map<String, BigDecimal> zoneRates() {
            return parseZones(shippingZones);
        }

        private static java.util.Map<String, BigDecimal> parseZones(String shippingZones) {
            var rates = new java.util.LinkedHashMap<String, BigDecimal>();
            if (shippingZones == null || shippingZones.isBlank()) return rates;
            for (String entry : shippingZones.split("[;,]")) {
                if (entry.isBlank()) continue;
                String[] kv = entry.split("=");
                BigDecimal rate;
                try { rate = kv.length == 2 ? new BigDecimal(kv[1].trim()) : null; } catch (NumberFormatException e) { rate = null; }
                if (rate == null || rate.signum() < 0 || kv[0].isBlank())
                    throw new IllegalStateException("APP_SHIPPING_ZONES must look like: Jalisco=149;Nuevo León=129");
                rates.put(kv[0].trim(), rate);
            }
            return rates;
        }

        /** Server-side shipping price for a destination state, or empty when the store does not deliver there. */
        public java.util.Optional<BigDecimal> shippingFor(String state, BigDecimal subtotal) {
            BigDecimal rate = null;
            for (var e : zoneRates().entrySet()) if (norm(e.getKey()).equals(norm(state))) rate = e.getValue();
            if (rate == null) {
                if (shippingOnlyZones) return java.util.Optional.empty();
                rate = shippingFlat;
            }
            if (shippingFreeFrom != null && subtotal.compareTo(shippingFreeFrom) >= 0) rate = BigDecimal.ZERO;
            return java.util.Optional.of(rate);
        }

        private static String norm(String s) {
            return s == null ? "" : java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "").trim().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record Admin(String email, String password) {
    }
}
