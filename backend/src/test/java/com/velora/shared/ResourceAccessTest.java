package com.velora.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.velora.shared.config.AppProperties;
import com.velora.shared.security.ResourceAccess;
import com.velora.shared.security.SecretTokens;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResourceAccessTest {

    private final SecretTokens tokens = new SecretTokens(new AppProperties(null,
            "test-secret-test-secret-test-secret-42", false, "MXN", null, null));
    private final UUID owner = UUID.randomUUID();

    @Test
    void ownerSessionIsAllowed() {
        assertThat(ResourceAccess.allowed(owner, null, Optional.of(owner), null, tokens)).isTrue();
    }

    @Test
    void anotherUserOrAnonymousWithoutTokenIsDenied() {
        assertThat(ResourceAccess.allowed(owner, null, Optional.of(UUID.randomUUID()), null, tokens)).isFalse();
        assertThat(ResourceAccess.allowed(owner, null, Optional.empty(), null, tokens)).isFalse();
    }

    @Test
    void guestNeedsTheExactSecretToken() {
        String token = tokens.newToken();
        String hash = tokens.hash(token);
        assertThat(ResourceAccess.allowed(null, hash, Optional.empty(), token, tokens)).isTrue();
        assertThat(ResourceAccess.allowed(null, hash, Optional.empty(), tokens.newToken(), tokens)).isFalse();
        assertThat(ResourceAccess.allowed(null, hash, Optional.empty(), "", tokens)).isFalse();
        assertThat(ResourceAccess.allowed(null, hash, Optional.of(UUID.randomUUID()), null, tokens)).isFalse();
    }

    @Test
    void storedValueIsAKeyedHashNotTheToken() {
        String token = tokens.newToken();
        assertThat(tokens.hash(token)).hasSize(64).isNotEqualTo(token);
    }

    @Test
    void tokenNeverBypassesOwnershipOfRegisteredResources() {
        String token = tokens.newToken();
        assertThat(ResourceAccess.allowed(owner, tokens.hash(token), Optional.empty(), token, tokens)).isFalse();
        assertThat(ResourceAccess.allowed(owner, tokens.hash(token), Optional.of(UUID.randomUUID()), token, tokens)).isFalse();
    }
}
