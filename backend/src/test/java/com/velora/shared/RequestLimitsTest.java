package com.velora.shared;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velora.shared.security.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class RequestLimitsTest {
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
        var filter = new ApiRequestFilter(new RequestLimits(), new ObjectMapper());
        for (int i = 0; i < 16; i++) {
            var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
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
        request.setContent(new byte[131073]);
        var response = new MockHttpServletResponse();
        new ApiRequestFilter(new RequestLimits(), new ObjectMapper()).doFilter(request, response,
                (req, res) -> { throw new AssertionError("Oversized body reached MVC"); });
        assertThat(response.getStatus()).isEqualTo(413);
    }
}
