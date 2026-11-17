package com.olvenio.payments.infrastructure.stripe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Stripe-Signature: t=<unix>,v1=<hex HMAC-SHA256 of "t." + raw body>[,v1=...]; other schemes are ignored. */
public final class StripeSignature {
    static final Duration TOLERANCE = Duration.ofMinutes(5);
    private StripeSignature() {}

    public static boolean valid(String header, byte[] body, String secret, Instant now) {
        if (header == null || header.length() > 2048 || body == null || secret == null || secret.isBlank()) return false;
        try {
            String timestamp = null;
            java.util.List<byte[]> signatures = new java.util.ArrayList<>();
            for (String item : header.split(",")) {
                String[] pair = item.trim().split("=", 2);
                if (pair.length != 2) return false;
                if ("t".equals(pair[0])) {
                    if (timestamp != null || !pair[1].matches("[0-9]{1,12}")) return false;
                    timestamp = pair[1];
                } else if ("v1".equals(pair[0]) && pair[1].matches("[a-fA-F0-9]{64}")) {
                    signatures.add(HexFormat.of().parseHex(pair[1]));
                }
            }
            if (timestamp == null || signatures.isEmpty()) return false;
            if (Duration.between(Instant.ofEpochSecond(Long.parseLong(timestamp)), now).abs().compareTo(TOLERANCE) > 0) return false;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            byte[] expected = mac.doFinal(body);
            boolean match = false;
            for (byte[] candidate : signatures) match |= MessageDigest.isEqual(expected, candidate);
            return match;
        } catch (Exception e) { return false; }
    }
}
