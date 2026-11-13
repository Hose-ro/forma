package com.velora.shared.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Fail closed when production accidentally uses a privileged or migration database account. */
@Component
@Profile({"prod", "staging"})
public class ProductionSecurityCheck implements ApplicationRunner {
    private final JdbcTemplate db;
    private final Environment env;
    private final AppProperties props;

    public ProductionSecurityCheck(JdbcTemplate db, Environment env, AppProperties props) {
        this.db = db;
        this.env = env;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (java.util.Arrays.asList(env.getActiveProfiles()).contains("dev"))
            throw new IllegalStateException("The dev and hosted profiles cannot be combined");
        String runtimeUser = env.getRequiredProperty("spring.datasource.username");
        if (runtimeUser.equals(env.getRequiredProperty("spring.flyway.user")))
            throw new IllegalStateException("Production requires separate runtime and migration database roles");
        Boolean privileged = db.queryForObject("select rolsuper or rolcreaterole or rolcreatedb or rolbypassrls from pg_roles where rolname = current_user", Boolean.class);
        if (!Boolean.FALSE.equals(privileged))
            throw new IllegalStateException("Production runtime database role must be unprivileged");
        if (!props.cookieSecure() || props.allowedOrigins().isEmpty()
                || props.allowedOrigins().stream().anyMatch(o -> !o.startsWith("https://")))
            throw new IllegalStateException("Production requires secure cookies and explicit HTTPS frontend origins");
    }
}
