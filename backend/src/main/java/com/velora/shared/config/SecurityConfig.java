package com.velora.shared.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velora.shared.api.GlobalExceptionHandler;
import com.velora.shared.security.ApiRequestFilter;
import com.velora.shared.security.RequestLimits;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Session-cookie auth (HttpOnly, SameSite=Lax, Secure in prod) + double-submit CSRF cookie
 * (XSRF-TOKEN cookie / X-XSRF-TOKEN header) for the Next.js frontend.
 */
@Configuration
public class SecurityConfig {

    public static final String CART_TOKEN_HEADER = "X-Cart-Token";
    public static final String ORDER_TOKEN_HEADER = "X-Order-Token";
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, CsrfTokenRepository csrfTokenRepository,
                                    SecurityContextRepository securityContextRepository,
                                    ObjectMapper objectMapper, RequestLimits limits) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        http
                .addFilterBefore(new ApiRequestFilter(limits, objectMapper), CsrfFilter.class)
                .cors(Customizer.withDefaults())
                .csrf(c -> c.csrfTokenRepository(csrfTokenRepository).csrfTokenRequestHandler(csrfHandler)
                        .ignoringRequestMatchers(new org.springframework.security.web.util.matcher.AntPathRequestMatcher("/api/v1/webhooks/mercadopago", "POST")))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(f -> f.changeSessionId()))
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(h -> h.frameOptions(f -> f.deny()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeProblem(res, objectMapper,
                                HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication required"))
                        .accessDeniedHandler((req, res, ex) -> {
                            if (req.getRequestURI().startsWith("/api/v1/admin/"))
                                org.slf4j.LoggerFactory.getLogger(SecurityConfig.class).warn("Administrative access denied");
                            writeProblem(res, objectMapper, HttpStatus.FORBIDDEN, "Forbidden", "Access denied");
                        }))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/products/**", "/api/v1/categories/**").permitAll()
                        .requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/session", "/api/v1/auth/register",
                                "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/me/**", "/api/v1/auth/me", "/api/v1/auth/logout").authenticated()
                        // Guest-capable: ownership/token checks are enforced in the application layer.
                        .requestMatchers("/api/v1/carts/**", "/api/v1/checkout/**", "/api/v1/orders/**", "/api/v1/payments/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/mercadopago").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    RequestLimits requestLimits() { return new RequestLimits(); }

    @Bean
    CsrfTokenRepository csrfTokenRepository(AppProperties props) {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookiePath("/");
        repo.setCookieCustomizer(c -> c.secure(props.cookieSecure()).sameSite("Lax"));
        return repo;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(12)));
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "Accept", "X-XSRF-TOKEN",
                CART_TOKEN_HEADER, ORDER_TOKEN_HEADER, IDEMPOTENCY_HEADER));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    private static void writeProblem(HttpServletResponse res, ObjectMapper mapper, HttpStatus status,
                                     String title, String detail) throws IOException {
        ProblemDetail pd = GlobalExceptionHandler.problem(status, title, detail);
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), pd);
    }
}
