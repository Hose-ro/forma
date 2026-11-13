package com.velora.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velora.shared.api.GlobalExceptionHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds JSON bodies, including chunked requests, before MVC parsing. Limits IP and account separately. */
public final class ApiRequestFilter extends OncePerRequestFilter {
    private static final int MAX_BODY = 128 * 1024;
    private final RequestLimits limits;
    private final ObjectMapper mapper;

    public ApiRequestFilter(RequestLimits limits, ObjectMapper mapper) {
        this.limits = limits;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean write = !java.util.Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
        String bucket = "read";
        int maximum = 240;
        Duration window = Duration.ofMinutes(1);
        if (write) {
            bucket = "write";
            maximum = 90;
            if (path.equals("/api/v1/auth/login")) { bucket = "login"; maximum = 15; }
            else if (path.equals("/api/v1/auth/register")) {
                bucket = "register"; maximum = 5; window = Duration.ofMinutes(15);
            } else if (path.equals("/api/v1/checkout/quotes") || path.equals("/api/v1/orders")
                    || path.startsWith("/api/v1/payments/")) { bucket = "checkout"; maximum = 15; }
            else if (path.equals("/api/v1/carts")) { bucket = "cart"; maximum = 20; }
            else if (path.startsWith("/api/v1/admin/")) { bucket = "admin"; maximum = 30; }
        }
        // Never trust caller-supplied X-Forwarded-For. Forwarding is disabled in server configuration.
        long wait = limits.retryAfter(bucket + ":ip:" + request.getRemoteAddr(), maximum, window);
        var user = CurrentUser.id();
        if (user.isPresent()) wait = Math.max(wait,
                limits.retryAfter(bucket + ":user:" + user.get(), maximum, window));
        if (wait > 0) {
            response.setHeader("Retry-After", Long.toString(wait));
            LoggerFactory.getLogger(ApiRequestFilter.class).warn("Request rate limit exceeded: {}", bucket);
            reject(response, HttpStatus.TOO_MANY_REQUESTS, "Too many requests");
            return;
        }
        if (request.getContentLengthLong() > MAX_BODY) {
            reject(response, HttpStatus.PAYLOAD_TOO_LARGE, "Request body too large");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            reject(response, HttpStatus.PAYLOAD_TOO_LARGE, "Request body too large");
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    public int read() { return input.read(); }
                    public boolean isFinished() { return input.available() == 0; }
                    public boolean isReady() { return true; }
                    public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
        }, response);
    }

    private void reject(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        mapper.writeValue(response.getOutputStream(), GlobalExceptionHandler.problem(status, status.getReasonPhrase(), detail));
    }
}
