package com.olvenio.shared;

import com.olvenio.shared.config.AppProperties;
import com.olvenio.shared.security.SecretTokens;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AccountMailTokenTest {
    private SecretTokens tokens(String secret) {
        return new SecretTokens(new AppProperties(null,secret,false,"MXN",null,null));
    }

    @Test void retryAndRestartReconstructOnlyTheSameMessageLink() {
        String secret="account-mail-test-secret-at-least-32-characters";
        UUID message=UUID.randomUUID();
        SecretTokens first=tokens(secret), restarted=tokens(secret);
        String link=first.accountMailToken(message);
        assertThat(link).matches("[A-Za-z0-9_-]{43}");
        assertThat(restarted.accountMailToken(message)).isEqualTo(link);
        assertThat(first.accountMailToken(UUID.randomUUID())).isNotEqualTo(link);
        assertThat(tokens(secret+"different").accountMailToken(message)).isNotEqualTo(link);
        assertThat(first.hash(link)).isNotEqualTo(link);
        assertThat(first.hash(message.toString())).isNotEqualTo(link);
        assertThat(first.matches(link,first.hash(link))).isTrue();
    }
}
