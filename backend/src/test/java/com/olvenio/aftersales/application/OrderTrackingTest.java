package com.olvenio.aftersales.application;

import com.olvenio.shared.config.AppProperties;
import com.olvenio.shared.security.SecretTokens;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderTrackingTest {
    private final SecretTokens tokens = new SecretTokens(new AppProperties(null,
            "tracking-test-secret-with-at-least-32-characters", false, "MXN", null, null));
    private final OrderTracking tracking = new OrderTracking(null, tokens, null);

    @Test void signedLinksAreScopedToAnOrderAndExpire() {
        Instant now = Instant.parse("2026-11-17T12:00:00Z");
        UUID id = UUID.randomUUID();
        String path = tracking.emailPath(id, now);
        String token = path.substring(path.indexOf('#') + 1);
        assertTrue(tracking.valid(id, token, now.plus(89, ChronoUnit.DAYS)));
        assertFalse(tracking.valid(UUID.randomUUID(), token, now));
        assertFalse(tracking.valid(id, token, now.plus(90, ChronoUnit.DAYS)));
        assertFalse(tracking.valid(id, tokens.newToken(), now));
        assertFalse(tracking.valid(id, token.replaceFirst("\\d", "9"), now));
    }
}
