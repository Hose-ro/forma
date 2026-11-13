package com.velora.shared.security;

import com.velora.shared.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Opaque bearer tokens for guest carts/orders. Only an HMAC-SHA256 (keyed with APP_SESSION_SECRET)
 * is persisted, so a database leak does not expose usable tokens.
 */
@Component
public class SecretTokens {

    private static final int TOKEN_BYTES = 32;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public SecretTokens(AppProperties props) {
        String secret = props.sessionSecret();
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("APP_SESSION_SECRET must be set (at least 32 characters)");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String token) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    /** Constant-time comparison of a presented token against a stored hash. */
    public boolean matches(String presentedToken, String storedHash) {
        if (presentedToken == null || presentedToken.isBlank() || storedHash == null || presentedToken.length() > 256) {
            return false;
        }
        return MessageDigest.isEqual(hash(presentedToken).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
