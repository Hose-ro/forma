package com.olvenio.shared.security;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signs a user out everywhere after a credential change. Sessions live in this process's memory, and so does
 * this registry: if sessions are ever moved to a shared store, this must move with them.
 */
public class SessionRevocations {
    /** Session attribute holding the epoch millis at which the session was authenticated. */
    public static final String AUTH_AT = "AUTH_AT";
    private final Map<UUID, Long> revokedAt = new ConcurrentHashMap<>();

    public void revoke(UUID userId) {
        revokedAt.put(userId, System.currentTimeMillis());
    }

    public boolean isRevoked(UUID userId, long authenticatedAt) {
        Long at = revokedAt.get(userId);
        return at != null && authenticatedAt <= at;
    }
}
