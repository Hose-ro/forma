package com.olvenio.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olvenio.shared.api.GlobalExceptionHandler;
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
    private static final int MAX_INVOICE_BODY = 3 * 1024 * 1024;
    private final RequestLimits limits;
    private final ObjectMapper mapper;

    private final byte[] proxySecret;
    private final SessionRevocations revocations;

    public ApiRequestFilter(RequestLimits limits, ObjectMapper mapper) {
        this(limits, mapper, null, null);
    }

    /** @param proxySecret shared with the storefront proxy; blank disables trust in its client-address header */
    public ApiRequestFilter(RequestLimits limits, ObjectMapper mapper, String proxySecret,
                            SessionRevocations revocations) {
        this.limits = limits;
        this.mapper = mapper;
        this.proxySecret = proxySecret == null || proxySecret.isBlank() ? null
                : proxySecret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        this.revocations = revocations;
    }

    public static final String PROXY_SECRET_HEADER = "X-Olvenio-Proxy-Secret";
    public static final String CLIENT_IP_HEADER = "X-Olvenio-Client-Ip";

    /**
     * Who is being limited. Requests that prove (shared secret) they crossed the storefront proxy are limited by
     * the visitor address that proxy observed; everything else, including callers that reach the API directly,
     * by the transport address. Generic forwarding headers are never trusted.
     */
    private String[] client(HttpServletRequest request) {
        String presented = request.getHeader(PROXY_SECRET_HEADER);
        if (proxySecret != null && presented != null && java.security.MessageDigest.isEqual(proxySecret,
                presented.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            String ip = request.getHeader(CLIENT_IP_HEADER);
            if (ip != null && ip.matches("[0-9A-Fa-f:.]{3,45}")) return new String[]{"ip:" + ip, "1"};
            // Trusted proxy that could not identify the visitor: one shared, much wider allowance.
            return new String[]{"proxy", "20"};
        }
        return new String[]{"ip:" + request.getRemoteAddr(), "1"};
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
        if (path.startsWith("/api/v1/checkout/postal-codes/")) { bucket = "postal"; maximum = 30; }
        if (write) {
            bucket = "write";
            maximum = 90;
            if (path.equals("/api/v1/auth/login")) { bucket = "login"; maximum = 15; }
            else if (path.equals("/api/v1/auth/register")) {
                bucket = "register"; maximum = 5; window = Duration.ofMinutes(15);
            } else if (path.startsWith("/api/v1/auth/password/") || path.startsWith("/api/v1/auth/email/")) {
                bucket = "recover"; maximum = 5; window = Duration.ofMinutes(15);
            }
            // One purchase touches quote, order and payment: separate budgets so a step never starves the next.
            else if (path.equals("/api/v1/checkout/quotes")) { bucket = "quote"; maximum = 15; }
            else if (path.endsWith("/tracking") && path.startsWith("/api/v1/orders/")) { bucket = "tracking"; maximum = 30; }
            else if (path.equals("/api/v1/orders")) { bucket = "order"; maximum = 15; }
            else if (path.startsWith("/api/v1/payments/")) { bucket = "payment"; maximum = 15; }
            else if (path.equals("/api/v1/carts")) { bucket = "cart"; maximum = 20; }
            else if (path.startsWith("/api/v1/admin/")) { bucket = "admin"; maximum = 30; }
        }
        // Never trust caller-supplied X-Forwarded-For. Forwarding is disabled in server configuration.
        String[] client = client(request);
        long wait = limits.retryAfter(bucket + ":" + client[0], maximum * Integer.parseInt(client[1]), window);
        var user = CurrentUser.id();
        if (user.isPresent() && revocations != null) {
            var session = request.getSession(false);
            Object authAt = session == null ? null : session.getAttribute(SessionRevocations.AUTH_AT);
            if (revocations.isRevoked(user.get(), authAt instanceof Long at ? at : 0L)) {
                if (session != null) session.invalidate();
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
                reject(response, HttpStatus.UNAUTHORIZED, "Session expired; sign in again");
                return;
            }
        }
        if (user.isPresent()) wait = Math.max(wait,
                limits.retryAfter(bucket + ":user:" + user.get(), maximum, window));
        if (wait > 0) {
            response.setHeader("Retry-After", Long.toString(wait));
            LoggerFactory.getLogger(ApiRequestFilter.class).warn("Request rate limit exceeded: {}", bucket);
            reject(response, HttpStatus.TOO_MANY_REQUESTS, "Too many requests");
            return;
        }
        int maxBody = "POST".equals(request.getMethod())
                && path.matches("/api/v1/admin/orders/[a-fA-F0-9-]{36}/invoice")
                && CurrentUser.isAdmin() ? MAX_INVOICE_BODY : MAX_BODY;
        if (request.getContentLengthLong() > maxBody) {
            reject(response, HttpStatus.PAYLOAD_TOO_LARGE, "Request body too large");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(maxBody + 1);
        if (body.length > maxBody) {
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
