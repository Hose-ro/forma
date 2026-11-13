package com.velora.shared.security;

import com.velora.shared.config.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Guest identity for browsers: an opaque secret kept in an HttpOnly cookie (never readable by JS).
 * Guest carts and orders store only its keyed hash. Non-browser clients may send the same value
 * in the X-Cart-Token / X-Order-Token headers instead.
 */
@Component
public class GuestSession {

    public static final String COOKIE = "VELORA_GUEST";
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{20,128}$");
    private static final Duration MAX_AGE = Duration.ofDays(90);

    private final SecretTokens tokens;
    private final boolean secure;

    public GuestSession(SecretTokens tokens, AppProperties props) {
        this.tokens = tokens;
        this.secure = props.cookieSecure();
    }

    public Optional<String> token(HttpServletRequest request) {
        if (request.getCookies() == null) return Optional.empty();
        for (Cookie c : request.getCookies()) {
            if (COOKIE.equals(c.getName()) && c.getValue() != null && TOKEN.matcher(c.getValue()).matches()) {
                return Optional.of(c.getValue());
            }
        }
        return Optional.empty();
    }

    /** Returns the guest token, issuing the cookie if the browser has none yet. */
    public String ensure(HttpServletRequest request, HttpServletResponse response) {
        return token(request).orElseGet(() -> {
            String token = tokens.newToken();
            response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, token).httpOnly(true)
                    .secure(secure).sameSite("Lax").path("/").maxAge(MAX_AGE).build().toString());
            return token;
        });
    }

    /** Explicit header token wins; otherwise the guest cookie (may be null). */
    public String resolve(String headerToken, HttpServletRequest request) {
        if (headerToken != null && !headerToken.isBlank()) {
            if (!TOKEN.matcher(headerToken).matches())
                throw new com.velora.shared.domain.InvalidRequestException("Invalid resource token");
            return headerToken;
        }
        return token(request).orElse(null);
    }
}
