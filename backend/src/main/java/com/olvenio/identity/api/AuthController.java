package com.olvenio.identity.api;

import com.olvenio.identity.application.AccountService;
import com.olvenio.identity.application.GoogleIdentityVerifier;
import com.olvenio.identity.application.GoogleIdentityVerifier.GoogleIdentity;
import com.olvenio.identity.application.IdentityDtos.GoogleCredential;
import com.olvenio.identity.application.IdentityDtos.GoogleLink;
import com.olvenio.identity.application.IdentityDtos.GoogleRegistration;
import com.olvenio.identity.application.IdentityDtos.LoginRequest;
import com.olvenio.identity.application.IdentityDtos.RegisterRequest;
import com.olvenio.identity.application.IdentityDtos.UserView;
import com.olvenio.shared.domain.RateLimitException;
import com.olvenio.shared.security.AuthUser;
import com.olvenio.shared.security.RequestLimits;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.access.AccessDeniedException;
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
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
public class AuthController {
    private static final String GOOGLE_CHALLENGE = AuthController.class.getName() + ".googleChallenge";
    private static final String GOOGLE_SIGNUP = AuthController.class.getName() + ".googleSignup";

    private final AccountService accounts;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final RequestLimits limits;
    private final com.olvenio.identity.application.AccountRecoveryService recovery;
    private final GoogleIdentityVerifier google;

    public AuthController(AccountService accounts, AuthenticationManager authenticationManager,
                          SecurityContextRepository contextRepository, CsrfTokenRepository csrfTokenRepository,
                          RequestLimits limits,
                          com.olvenio.identity.application.AccountRecoveryService recovery,
                          GoogleIdentityVerifier google) {
        this.recovery = recovery;
        this.google = google;
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
        if (recovery.verificationAvailable()) recovery.requestVerification(user.id());
        return user;
    }

    public record ForgotRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Email
                                @jakarta.validation.constraints.Size(max = 320) String email) {
    }

    public record ResetRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200) String token,
                               @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min = 10, max = 72) String password) {
        @jakarta.validation.constraints.AssertTrue(message = "password must not exceed 72 UTF-8 bytes")
        public boolean isPasswordWithinBcryptLimit() {
            return password == null || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72;
        }
    }

    public record TokenRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200) String token) {
    }

    @Operation(summary = "Request a password-reset link. Always 202, whether or not the address has an account")
    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody ForgotRequest req) {
        recovery.requestPasswordReset(req.email());
    }

    @Operation(summary = "Set a new password with a single-use reset token; ends every existing session")
    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetRequest req) {
        recovery.resetPassword(req.token(), req.password());
    }

    @Operation(summary = "Confirm the account's email with a single-use token")
    @PostMapping("/email/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody TokenRequest req) {
        recovery.verifyEmail(req.token());
    }

    @Operation(summary = "Send the verification link again to the signed-in account")
    @PostMapping("/email/verification")
    public ResponseEntity<Void> resendVerification(@AuthenticationPrincipal AuthUser user) {
        if (!recovery.verificationAvailable()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        if (!recovery.requestVerification(user.id())) throw new RateLimitException();
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Log in with email and password (session cookie)")
    @PostMapping("/login")
    public UserView login(@Valid @RequestBody LoginRequest req, HttpServletRequest request,
                          HttpServletResponse response) {
        AuthUser user = startSession(req.email(), req.password(), request, response);
        return accounts.profile(user.id());
    }

    private record GoogleChallenge(String nonce, UUID userId, Instant expiresAt) {}
    private record GoogleSignup(UUID ticket, GoogleIdentity identity, Instant expiresAt) {}
    public record GoogleProfile(UUID ticket, String email, String firstName, String lastName) {}
    public record GoogleLoginResult(UserView user, GoogleProfile registration) {}

    @PostMapping("/google/challenge")
    public Map<String, String> googleChallenge(@AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        String clientId = google.clientId();
        if (user != null && user.isAdmin()) throw new AccessDeniedException("Customer access required");
        HttpSession session = request.getSession(true);
        UUID userId = user == null ? null : user.id();
        if (session.getAttribute(GOOGLE_CHALLENGE) instanceof GoogleChallenge current
                && Objects.equals(current.userId(), userId) && Instant.now().isBefore(current.expiresAt())) {
            return Map.of("clientId", clientId, "nonce", current.nonce());
        }
        String nonce = UUID.randomUUID().toString();
        session.setAttribute(GOOGLE_CHALLENGE, new GoogleChallenge(nonce, userId, Instant.now().plusSeconds(900)));
        return Map.of("clientId", clientId, "nonce", nonce);
    }

    @PostMapping("/google/login")
    public GoogleLoginResult googleLogin(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody GoogleCredential req,
                                         HttpServletRequest request, HttpServletResponse response) {
        requireAnonymous(user);
        HttpSession session = request.getSession(false);
        GoogleIdentity identity = verifyGoogle(req.credential(), session, null);
        UserView linked = accounts.googleLogin(identity);
        if (linked != null) {
            startGoogleSession(linked, request, response);
            return new GoogleLoginResult(linked, null);
        }
        session.removeAttribute(GOOGLE_CHALLENGE);
        UUID ticket = UUID.randomUUID();
        session.setAttribute(GOOGLE_SIGNUP, new GoogleSignup(ticket, identity, Instant.now().plusSeconds(900)));
        return new GoogleLoginResult(null, new GoogleProfile(ticket, identity.email(), identity.firstName(), identity.lastName()));
    }

    @PostMapping("/google/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView googleRegister(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody GoogleRegistration req,
                                   HttpServletRequest request, HttpServletResponse response) {
        requireAnonymous(user);
        google.clientId();
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(GOOGLE_SIGNUP) instanceof GoogleSignup signup)
                || !signup.ticket().equals(req.ticket()) || !Instant.now().isBefore(signup.expiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Google signup expired");
        }
        UserView registered = accounts.googleRegister(signup.identity(), req);
        startGoogleSession(registered, request, response);
        return registered;
    }

    @PostMapping("/google/link")
    public UserView googleLink(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody GoogleLink req,
                               HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        GoogleIdentity identity = verifyGoogle(req.credential(), session, user.id());
        UserView linked = accounts.linkGoogle(user.id(), identity, req.password());
        session.removeAttribute(GOOGLE_CHALLENGE);
        return linked;
    }

    private GoogleIdentity verifyGoogle(String credential, HttpSession session, UUID userId) {
        google.clientId();
        if (session == null || !(session.getAttribute(GOOGLE_CHALLENGE) instanceof GoogleChallenge challenge)
                || !Objects.equals(challenge.userId(), userId) || !Instant.now().isBefore(challenge.expiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Google challenge expired");
        }
        return google.verify(credential, challenge.nonce());
    }

    private static void requireAnonymous(AuthUser user) {
        if (user != null) throw new AccessDeniedException("Sign out before switching accounts");
    }

    private void startGoogleSession(UserView user, HttpServletRequest request, HttpServletResponse response) {
        var principal = new AuthUser(user.id(), user.email(), null, user.role(), true);
        saveSession(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()),
                request, response);
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
                cookie.getName() == null ? "OLVENIO_SESSION" : cookie.getName(), "")
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
        String normalized = com.olvenio.identity.domain.User.normalizeEmail(email);
        // A separate account bucket prevents rotating source IPs from bypassing login limits.
        String fingerprint;
        try {
            fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        long wait = limits.retryAfter("login:account:" + fingerprint, 10, java.time.Duration.ofMinutes(15));
        if (wait > 0) {
            response.setHeader("Retry-After", Long.toString(wait));
            throw new com.olvenio.shared.domain.RateLimitException();
        }
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(normalized, password));
        } catch (org.springframework.security.core.AuthenticationException ex) {
            org.slf4j.LoggerFactory.getLogger(AuthController.class).info("Authentication failed");
            throw ex;
        }
        saveSession(auth, request, response);
        return (AuthUser) auth.getPrincipal();
    }

    private void saveSession(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        // Session fixation protection: rotate the session id on privilege change.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        } else {
            request.getSession(true);
        }
        HttpSession session = request.getSession();
        session.removeAttribute(GOOGLE_CHALLENGE);
        session.removeAttribute(GOOGLE_SIGNUP);
        session.setAttribute(com.olvenio.shared.security.SessionRevocations.AUTH_AT, System.currentTimeMillis());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        // Rotate CSRF token after authentication; client must re-fetch /auth/csrf.
        csrfTokenRepository.saveToken(null, request, response);
    }
}
