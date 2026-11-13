package com.velora.shared;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.velora.shared.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

class ProductionSecurityCheckTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final AppProperties props = new AppProperties("https://store.example", "test-secret-test-secret-test-secret-42", true, "MXN", null, null);

    @Test void productionRejectsPrivilegedRuntimeRole() {
        var env = new MockEnvironment().withProperty("spring.datasource.username", "runtime")
                .withProperty("spring.flyway.user", "migrations");
        env.setActiveProfiles("prod");
        when(db.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        assertThatThrownBy(() -> new ProductionSecurityCheck(db, env, props).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unprivileged");
    }

    @Test void productionRejectsSharedMigrationCredentialsAndDevProfile() {
        var env = new MockEnvironment().withProperty("spring.datasource.username", "runtime")
                .withProperty("spring.flyway.user", "runtime");
        env.setActiveProfiles("prod");
        assertThatThrownBy(() -> new ProductionSecurityCheck(db, env, props).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("separate");
        env.setActiveProfiles("prod", "dev");
        assertThatThrownBy(() -> new ProductionSecurityCheck(db, env, props).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be combined");
        verifyNoInteractions(db);
    }
}
