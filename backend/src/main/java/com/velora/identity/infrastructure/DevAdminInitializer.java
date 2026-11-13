package com.velora.identity.infrastructure;

import com.velora.identity.domain.Role;
import com.velora.identity.domain.User;
import com.velora.shared.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates a development admin from APP_ADMIN_EMAIL / APP_ADMIN_PASSWORD. Idempotent. In production it bootstraps the first admin: remove APP_ADMIN_PASSWORD from the host after the first start. */
@Component
@Profile({"dev", "staging", "prod"})
public class DevAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevAdminInitializer.class);
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    public DevAdminInitializer(UserRepository users, PasswordEncoder encoder, AppProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email = props.admin().email();
        String password = props.admin().password();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            log.info("Dev admin not configured (APP_ADMIN_EMAIL / APP_ADMIN_PASSWORD); skipping");
            return;
        }
        if (password.length() < 10 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            log.warn("APP_ADMIN_PASSWORD must have at least 10 characters and at most 72 UTF-8 bytes; dev admin not created");
            return;
        }
        if (users.existsByEmailIgnoreCase(email)) {
            return;
        }
        users.save(new User(email, encoder.encode(password), "Admin", "FORMA", null, Role.ADMIN));
        log.info("Dev admin created");
    }
}
