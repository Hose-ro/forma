package com.olvenio.shared;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olvenio.shared.security.*;
import jakarta.servlet.http.HttpSession;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RequestLimitsTest {
    private static final int MAX_BODY = 128 * 1024;
    private static final String INVOICE = "/v1/admin/orders/11111111-1111-1111-1111-111111111111/invoice";

    @Test void boundedCountersDoNotEvictActiveLimits() {
        var limits = new RequestLimits(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 2);
        assertThat(limits.retryAfter("account:1", 1, Duration.ofMinutes(1))).isZero();
        assertThat(limits.retryAfter("account:2", 1, Duration.ofMinutes(1))).isZero();
        assertThat(limits.retryAfter("account:3", 1, Duration.ofMinutes(1))).isPositive();
        assertThat(limits.retryAfter("account:1", 1, Duration.ofMinutes(1))).isEqualTo(60);
    }

    @Test void windowsExpire() {
        final Instant[] now = {Instant.EPOCH};
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now[0]; }
        };
        var limits = new RequestLimits(clock, 2);
        limits.retryAfter("ip", 1, Duration.ofSeconds(10));
        assertThat(limits.retryAfter("ip", 1, Duration.ofSeconds(10))).isEqualTo(10);
        now[0] = now[0].plusSeconds(10);
        assertThat(limits.retryAfter("ip", 1, Duration.ofSeconds(10))).isZero();
    }

    @Test void forwardedHeadersCannotBypassLimits() throws Exception {
        var filter = filter();
        for (int i = 0; i < 16; i++) {
            var request = request("POST", "/api/v1/auth/login", null);
            request.setRemoteAddr("127.0.0.1");
            request.addHeader("X-Forwarded-For", "192.0.2." + i);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {});
            assertThat(response.getStatus()).isEqualTo(i < 15 ? 200 : 429);
        }
    }

    @Test void bodyWithoutContentLengthIsStillBounded() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/carts") {
            public long getContentLengthLong() { return -1; }
        };
        request.setServletPath("/api/v1/carts");
        request.setContent(new byte[MAX_BODY + 1]);
        var response = new MockHttpServletResponse();
        filter().doFilter(request, response,
                (req, res) -> { throw new AssertionError("Oversized body reached MVC"); });
        assertThat(response.getStatus()).isEqualTo(413);
    }

    @ParameterizedTest
    @CsvSource({
        "/v1/auth/login, 15, 60",
        "/v1/auth/password/forgot, 5, 900",
        "/v1/auth/email/verify, 5, 900",
        "/v1/auth/google/login, 20, 60",
        "/v1/auth/google/link, 10, 900",
        "/v1/auth/register, 5, 900",
        "/v1/auth/register/verify, 15, 900",
        "/v1/auth/register/resend, 5, 900"
    })
    void authQuotasUseTheServletMapping(String pathInfo, int maximum, String retryAfter) throws Exception {
        var filter = filter();
        for (int i = 0; i < maximum; i++) {
            var chain = new MockFilterChain();
            filter.doFilter(mappedRequest(pathInfo), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isNotNull();
        }

        var response = new MockHttpServletResponse();
        filter.doFilter(mappedRequest(pathInfo), response,
                (req, res) -> { throw new AssertionError("Rate-limited request reached MVC"); });
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo(retryAfter);
    }

    @ParameterizedTest
    @CsvSource({"ADMIN, 3145728", "CUSTOMER, 131072"})
    void invoiceUploadUsesTheLimitForTheCurrentRole(String role, int maximum) throws Exception {
        var principal = new AuthUser(UUID.randomUUID(), "invoice@example.com", null, role, true);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            var filter = filter();
            var accepted = mappedRequest(INVOICE);
            accepted.setContent(new byte[maximum]);
            var chain = new MockFilterChain();
            filter.doFilter(accepted, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest().getInputStream().readAllBytes()).hasSize(maximum);

            var oversized = mappedRequest(INVOICE);
            oversized.setContent(new byte[maximum + 1]);
            var response = new MockHttpServletResponse();
            filter.doFilter(oversized, response,
                    (req, res) -> { throw new AssertionError("Oversized invoice reached MVC"); });
            assertThat(response.getStatus()).isEqualTo(413);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentSessionInvalidationReturnsUnauthorized(boolean duringRead) throws Exception {
        var user = new AuthUser(UUID.randomUUID(), "customer@example.com", null, "CUSTOMER", true);
        var revocations = new SessionRevocations();
        revocations.revoke(user.id());
        var filter = new ApiRequestFilter(new RequestLimits(), new ObjectMapper(), null, revocations);
        var request = request("GET", "/api/v1/me", null);
        var session = mock(HttpSession.class);
        var response = new MockHttpServletResponse();
        request.setSession(session);
        if (duringRead) {
            when(session.getAttribute(SessionRevocations.AUTH_AT)).thenThrow(IllegalStateException.class);
        } else {
            when(session.getAttribute(SessionRevocations.AUTH_AT)).thenReturn(1L);
            doThrow(IllegalStateException.class).when(session).invalidate();
        }

        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        try {
            filter.doFilter(request, response,
                    (req, res) -> { throw new AssertionError("Expired session reached MVC"); });
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html", "/error"})
    void nonApiRequestsKeepTheirNormalDispatch(String path) throws Exception {
        var request = request("GET", path, null);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter().doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getHeader("Cache-Control")).isNull();
    }

    private static ApiRequestFilter filter() {
        var limits = new RequestLimits(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 100);
        return new ApiRequestFilter(limits, new ObjectMapper(), null, new SessionRevocations());
    }

    private static MockHttpServletRequest mappedRequest(String pathInfo) {
        var request = request("POST", "/api", pathInfo);
        request.setRequestURI("/shop/servlet-mapping-fixture");
        return request;
    }

    private static MockHttpServletRequest request(String method, String servletPath, String pathInfo) {
        var request = new MockHttpServletRequest(method, "/shop" + servletPath + (pathInfo == null ? "" : pathInfo));
        request.setContextPath("/shop");
        request.setServletPath(servletPath);
        request.setPathInfo(pathInfo);
        return request;
    }
}
