package com.velora.shared.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Access rule for guest-capable resources (carts, orders): the owning user, or anyone presenting
 * the resource's secret token. A bare UUID is never enough.
 */
public final class ResourceAccess {
    private ResourceAccess() {
    }

    public static boolean allowed(UUID ownerUserId, String storedTokenHash, Optional<UUID> requester,
                                  String presentedToken, SecretTokens tokens) {
        if (ownerUserId != null) return requester.filter(ownerUserId::equals).isPresent();
        return storedTokenHash != null && tokens.matches(presentedToken, storedTokenHash);
    }
}
