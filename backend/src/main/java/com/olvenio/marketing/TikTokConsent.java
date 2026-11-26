package com.olvenio.marketing;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TikTokConsent {
    static final String TICKET = "OLVENIO_MARKETING";
    static final String DENIED = "OLVENIO_MARKETING_DENIED";
    private static final Duration LIFETIME = Duration.ofDays(180);
    private static final Pattern ATTRIBUTION = Pattern.compile("[A-Za-z0-9._~-]{8,512}");
    private final JdbcTemplate db;
    private final TikTokSettings settings;

    record Preference(boolean enabled, String pixelId, Instant expiresAt) {}
    record Grant(UUID id, String ttclid, String ttp) {}

    TikTokConsent(JdbcTemplate db, TikTokSettings settings) {
        this.db = db;
        this.settings = settings;
    }

    Preference status(HttpServletRequest request) {
        Instant expiresAt = !settings.enabled || denied(request) ? null : expiry(ticket(request));
        return new Preference(settings.enabled, expiresAt == null ? null : settings.pixel, expiresAt);
    }

    @Transactional
    public Preference update(HttpServletRequest request, HttpServletResponse response, boolean allow, String clickId) {
        UUID existing = ticket(request);
        if (!allow) {
            if (existing != null) {
                db.update("UPDATE marketing_consents SET revoked_at = now(), ttclid = NULL, ttp = NULL WHERE id = ? AND revoked_at IS NULL", existing);
                db.update("""
                        UPDATE marketing_events SET status = 'SKIPPED', ttp = NULL, ttclid = NULL, claim_id = NULL
                        WHERE consent_id = ? AND status IN ('PENDING', 'SENDING')
                        """, existing);
                db.update("UPDATE marketing_events SET ttp = NULL, ttclid = NULL WHERE consent_id = ? AND (ttp IS NOT NULL OR ttclid IS NOT NULL)", existing);
            }
            setCookie(response, TICKET, "", Duration.ZERO, true);
            setCookie(response, DENIED, "1", LIFETIME, false);
            return new Preference(settings.enabled, null, null);
        }
        if (!settings.enabled) return new Preference(false, null, null);
        String ttclid = attribution(clickId);
        Instant expiresAt = expiry(existing);
        if (expiresAt == null) {
            existing = UUID.randomUUID();
            expiresAt = Instant.now().plus(LIFETIME);
            db.update("INSERT INTO marketing_consents (id, expires_at, ttclid) VALUES (?, ?, ?)",
                    existing, java.sql.Timestamp.from(expiresAt), ttclid);
        } else if (ttclid != null) {
            db.update("UPDATE marketing_consents SET ttclid = ? WHERE id = ? AND revoked_at IS NULL", ttclid, existing);
        }
        setCookie(response, TICKET, existing.toString(), Duration.between(Instant.now(), expiresAt), true);
        setCookie(response, DENIED, "", Duration.ZERO, false);
        return new Preference(true, settings.pixel, expiresAt);
    }

    Grant grant(HttpServletRequest request) {
        UUID id = ticket(request);
        if (!settings.enabled || id == null || denied(request)) return null;
        String requestTtp = attribution(cookie(request, "_ttp"));
        return db.query("""
                SELECT ttclid, ttp FROM marketing_consents
                WHERE id = ? AND revoked_at IS NULL AND expires_at > now()
                """, (row, index) -> new Grant(id, row.getString("ttclid"), requestTtp != null ? requestTtp : row.getString("ttp")), id)
                .stream().findFirst().orElse(null);
    }

    void attribute(HttpServletRequest request, String ttp, String ttclid) {
        if (!settings.enabled || denied(request)) return;
        UUID id = ticket(request);
        String cookie = attribution(ttp);
        String click = attribution(ttclid);
        if (id == null || (cookie == null && click == null)) return;
        db.update("""
                UPDATE marketing_consents SET ttp = coalesce(?, ttp), ttclid = coalesce(?, ttclid)
                WHERE id = ? AND revoked_at IS NULL AND expires_at > now()
                """, cookie, click, id);
    }

    String page(HttpServletRequest request) {
        String referer = request.getHeader(HttpHeaders.REFERER);
        if (referer == null || !referer.startsWith(settings.origin + "/")) return null;
        try {
            String path = URI.create(referer).getRawPath();
            if (path == null) return null;
            return path;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Instant expiry(UUID id) {
        if (id == null) return null;
        return db.query("""
                SELECT expires_at FROM marketing_consents
                WHERE id = ? AND revoked_at IS NULL AND expires_at > now()
                """, (row, index) -> row.getTimestamp(1).toInstant(), id).stream().findFirst().orElse(null);
    }

    private void setCookie(HttpServletResponse response, String name, String value, Duration age, boolean httpOnly) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, value)
                .path("/").httpOnly(httpOnly).secure(settings.secureCookie).sameSite("Lax").maxAge(age).build().toString());
    }

    private static boolean denied(HttpServletRequest request) {
        return "1".equals(cookie(request, DENIED));
    }

    private static UUID ticket(HttpServletRequest request) {
        String value = cookie(request, TICKET);
        if (value == null || value.length() != 36) return null;
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException ex) { return null; }
    }

    private static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    static String attribution(String value) {
        return value != null && ATTRIBUTION.matcher(value).matches() ? value : null;
    }
}
