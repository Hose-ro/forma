package com.velora.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI veloraOpenApi() {
        return new OpenAPI().info(new Info().title("VELORA API").version("v1")
                .description("""
                        Session-cookie authentication. Unsafe methods require CSRF: call GET /api/v1/auth/csrf \
                        and send the token in the X-XSRF-TOKEN header (cookie XSRF-TOKEN is also set). \
                        Guests are identified by the HttpOnly VELORA_GUEST cookie (or X-Cart-Token / X-Order-Token headers); \
                        POST /api/v1/orders requires Idempotency-Key. No real payments are processed."""));
    }
}
