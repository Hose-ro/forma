package com.velora.identity.api;

import com.velora.identity.application.AccountService;
import com.velora.identity.application.IdentityDtos.LoginRequest;
import com.velora.identity.application.IdentityDtos.RegisterRequest;
import com.velora.identity.application.IdentityDtos.UserView;
import com.velora.shared.security.AuthUser;
import com.velora.shared.security.RequestLimits;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
public class AuthController {

    private final AccountService accounts;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final RequestLimits limits;

    public AuthController(AccountService accounts, AuthenticationManager authenticationManager,
                          SecurityContextRepository contextRepository, CsrfTokenRepository csrfTokenRepository,
                          RequestLimits limits) {
        this.accounts = accounts;
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.limits = limits;
    }

    @Operation(summary = "Issue CSRF token (also set as XSRF-TOKEN cookie). Send it as X-XSRF-TOKEN on unsafe requests.")
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @Operation(summary = "Register a CUSTOMER account and start a session")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView register(@Valid @RequestBody RegisterRequest req, HttpServletRequest request,
                             HttpServletResponse response) {
        UserView user = accounts.register(req);
        startSession(req.email(), req.password(), request, response);
        return user;
    }

    @Operation(summary = "Log in with email and password (session cookie)")
    @PostMapping("/login")
    public UserView login(@Valid @RequestBody LoginRequest req, HttpServletRequest request,
                          HttpServletResponse response) {
        AuthUser user = startSession(req.email(), req.password(), request, response);
        return accounts.profile(user.id());
    }

    @Operation(summary = "Log out and invalidate the session")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        csrfTokenRepository.saveToken(null, request, response);
        var cookie = request.getServletContext().getSessionCookieConfig();
        response.addHeader("Set-Cookie", org.springframework.http.ResponseCookie.from(
                cookie.getName() == null ? "VELORA_SESSION" : cookie.getName(), "")
                .path(cookie.getPath() == null ? "/" : cookie.getPath()).httpOnly(true)
                .secure(cookie.isSecure()).sameSite("Lax").maxAge(0).build().toString());
    }

    @Operation(summary = "Current session user, or 204 when anonymous (never 401)")
    @GetMapping("/session")
    public ResponseEntity<UserView> session(@AuthenticationPrincipal AuthUser user) {
        return user == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(accounts.profile(user.id()));
    }

    @Operation(summary = "Current user profile")
    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal AuthUser user) {
        return accounts.profile(user.id());
    }

    private AuthUser startSession(String email, String password, HttpServletRequest request,
                                  HttpServletResponse response) {
        String normalized = com.velora.identity.domain.User.normalizeEmail(email);
        // A separate account bucket prevents rotating source IPs from bypassing login limits.
        String fingerprint;
        try {
            fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        long wait = limits.retryAfter("login:account:" + fingerprint, 10, java.time.Duration.ofMinutes(15));
        if (wait > 0) {
            response.setHeader("Retry-After", Long.toString(wait));
            throw new com.velora.shared.domain.RateLimitException();
        }
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(normalized, password));
        } catch (org.springframework.security.core.AuthenticationException ex) {
            org.slf4j.LoggerFactory.getLogger(AuthController.class).info("Authentication failed");
            throw ex;
        }
        // Session fixation protection: rotate the session id on privilege change.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        } else {
            request.getSession(true);
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        // Rotate CSRF token after authentication; client must re-fetch /auth/csrf.
        csrfTokenRepository.saveToken(null, request, response);
        return (AuthUser) auth.getPrincipal();
    }
}
