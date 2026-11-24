package com.olvenio.shared;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.olvenio.cart.api.CartController;
import com.olvenio.cart.application.CartService;
import com.olvenio.checkout.api.CheckoutController;
import com.olvenio.checkout.application.CheckoutService;
import com.olvenio.identity.api.AuthController;
import com.olvenio.identity.application.AccountService;
import com.olvenio.identity.application.EmailRegistrationService;
import com.olvenio.identity.application.GoogleIdentityVerifier;
import com.olvenio.identity.application.GoogleIdentityVerifier.GoogleIdentity;
import com.olvenio.identity.application.IdentityDtos.UserView;
import com.olvenio.shared.config.*;
import com.olvenio.shared.security.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({AuthController.class, CartController.class, CheckoutController.class})
@Import({SecurityConfig.class, SecurityRegressionTest.Props.class})
class SecurityRegressionTest {
    @TestConfiguration @EnableConfigurationProperties(AppProperties.class) static class Props {}
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;
    @Autowired SessionRevocations revocations;
    @MockitoBean AccountService accounts;
    @MockitoBean EmailRegistrationService registration;
    @MockitoBean GoogleIdentityVerifier google;
    @MockitoBean com.olvenio.identity.application.AccountRecoveryService recovery;
    @MockitoBean UserDetailsService users;
    @MockitoBean CartService carts;
    @MockitoBean CheckoutService checkout;
    @MockitoBean GuestSession guest;

    @Test void privateEndpointRequiresAuthenticationAndIsNeverCached() throws Exception {
        mvc.perform(get("/api/v1/auth/me").servletPath("/api/v1/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void loginAndCartMutationsRequireCsrf() throws Exception {
        mvc.perform(post("/api/v1/auth/login").servletPath("/api/v1/auth/login").contentType("application/json")
                .content("{\"email\":\"test@example.com\",\"password\":\"test-password\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/carts").servletPath("/api/v1/carts")).andExpect(status().isForbidden());
        verifyNoInteractions(accounts, carts, users);
    }

    @ParameterizedTest
    @CsvSource({"/api/v1/auth/session, true", "/swagger-ui.html, true", "/error, false"})
    void revokedSessionsAreInvalidatedBeforeContinuing(String path, boolean hasAuthenticationTime) throws Exception {
        UUID id = UUID.randomUUID();
        var principal = new AuthUser(id, "revoked@example.com", null, "CUSTOMER", true);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        if (hasAuthenticationTime) session.setAttribute(SessionRevocations.AUTH_AT, 1L);
        revocations.revoke(id);

        mvc.perform(get("/shop" + path).contextPath("/shop").servletPath(path).session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Session expired; sign in again"));

        assertThat(session.isInvalid()).isTrue();
        verifyNoInteractions(accounts);
    }

    @Test void googleLinkRequiresCustomerSessionAndCsrf() throws Exception {
        String body = """
                {"credential":"google-token","password":"test-password"}
                """;
        AuthUser customer = new AuthUser(UUID.randomUUID(), "customer@example.com", null, "CUSTOMER", true);
        AuthUser admin = new AuthUser(UUID.randomUUID(), "admin@example.com", null, "ADMIN", true);
        mvc.perform(post("/api/v1/auth/google/link").servletPath("/api/v1/auth/google/link").with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/google/link").servletPath("/api/v1/auth/google/link").with(user(admin)).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/google/link").servletPath("/api/v1/auth/google/link").with(user(customer)).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        verifyNoInteractions(google, accounts);
    }

    @Test void googleLoginUsesChallengeFromSameSessionAndRotatesSession() throws Exception {
        when(google.clientId()).thenReturn("test-client-id");
        var identity = new GoogleIdentity("test-subject", "google-user@gmail.com", "Test", "User", true);
        var profile = new UserView(UUID.randomUUID(), identity.email(), "Test", "User", null, "CUSTOMER",
                Instant.now(), true, List.of(), true);
        var challenge = mvc.perform(post("/api/v1/auth/google/challenge").servletPath("/api/v1/auth/google/challenge").with(csrf()))
                .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) challenge.getRequest().getSession(false);
        String nonce = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(challenge.getResponse().getContentAsString()).get("nonce").asText();
        String oldSessionId = session.getId();
        when(google.verify("google-token", nonce)).thenReturn(identity);
        when(accounts.googleLogin(identity)).thenReturn(profile);
        String body = """
                {"credential":"google-token"}
                """;
        mvc.perform(post("/api/v1/auth/google/login").servletPath("/api/v1/auth/google/login").with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isGone());
        mvc.perform(post("/api/v1/auth/google/login").servletPath("/api/v1/auth/google/login").session(session).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(profile.id().toString()));
        assertThat(session.getId()).isNotEqualTo(oldSessionId);
        when(accounts.profile(profile.id())).thenReturn(profile);
        mvc.perform(get("/api/v1/auth/session").servletPath("/api/v1/auth/session").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.googleLinked").value(true));
        verify(google, times(1)).verify("google-token", nonce);
    }

    @Test void googleRegistrationRequiresSessionTicketAndExplicitConsent() throws Exception {
        when(google.clientId()).thenReturn("test-client-id");
        var identity = new GoogleIdentity("new-subject", "new-user@gmail.com", "Test", "User", true);
        var profile = new UserView(UUID.randomUUID(), identity.email(), "Test", "User", null, "CUSTOMER",
                Instant.now(), true, List.of(), true);
        when(google.verify(eq("google-token"), anyString())).thenReturn(identity);
        when(accounts.googleRegister(eq(identity), any())).thenReturn(profile);
        var challenge = mvc.perform(post("/api/v1/auth/google/challenge").servletPath("/api/v1/auth/google/challenge").with(csrf()))
                .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) challenge.getRequest().getSession(false);
        var login = mvc.perform(post("/api/v1/auth/google/login").servletPath("/api/v1/auth/google/login").session(session).with(csrf()).contentType("application/json")
                .content("{\"credential\":\"google-token\"}")).andExpect(status().isOk()).andReturn();
        String ticket = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(login.getResponse().getContentAsString()).at("/registration/ticket").asText();
        String body = """
                {"ticket":"%s","firstName":"Test","lastName":"User","termsAccepted":%b}
                """;
        mvc.perform(post("/api/v1/auth/google/register").servletPath("/api/v1/auth/google/register").session(session).with(csrf()).contentType("application/json")
                .content(body.formatted(ticket, false))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/google/register").servletPath("/api/v1/auth/google/register").session(session).with(csrf()).contentType("application/json")
                .content(body.formatted(UUID.randomUUID(), true))).andExpect(status().isGone());
        mvc.perform(post("/api/v1/auth/google/register").servletPath("/api/v1/auth/google/register").session(session).with(csrf()).contentType("application/json")
                .content(body.formatted(ticket, true))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(identity.email()));
        verify(accounts, times(1)).googleRegister(eq(identity), any());
    }

    @Test void rejectsPriceAndPaymentStatusMassAssignment() throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/orders").servletPath("/api/v1/orders").with(csrf()).contentType("application/json")
                .header("Idempotency-Key", "checkout-test-key")
                .content("{\"quoteId\":\"" + id + "\",\"total\":0,\"paymentStatus\":\"APPROVED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Invalid JSON request"));
        String itemsPath = "/api/v1/carts/" + id + "/items";
        mvc.perform(post(itemsPath).servletPath(itemsPath).with(csrf()).contentType("application/json")
                .content("{\"variantId\":\"" + id + "\",\"quantity\":1,\"price\":0}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(carts, checkout);
    }

    @Test void rejectsRoleInjectionInvalidQuantityAndOversizedJson() throws Exception {
        mvc.perform(post("/api/v1/auth/register").servletPath("/api/v1/auth/register").with(csrf()).contentType("application/json")
                .content("{\"email\":\"test@example.com\",\"password\":\"test-password\",\"firstName\":\"Test\",\"lastName\":\"User\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        String itemsPath = "/api/v1/carts/" + UUID.randomUUID() + "/items";
        mvc.perform(post(itemsPath).servletPath(itemsPath).with(csrf()).contentType("application/json")
                .content("{\"variantId\":\"" + UUID.randomUUID() + "\",\"quantity\":100}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/carts").servletPath("/api/v1/carts").with(csrf()).contentType("application/json").content("x".repeat(131073)))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(accounts, carts);
    }

    @Test void registrationRequiresExplicitConsentAndMatchingPasswords() throws Exception {
        String registration = """
                {"email":"test@example.com","firstName":"Test","lastName":"User","password":"test-password",
                 "passwordConfirmation":"%s"%s}
                """;
        for (String consent : List.of("", ",\"termsAccepted\":false")) {
            mvc.perform(post("/api/v1/auth/register").servletPath("/api/v1/auth/register").with(csrf()).contentType("application/json")
                    .content(registration.formatted("test-password", consent))).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/auth/register").servletPath("/api/v1/auth/register").with(csrf()).contentType("application/json")
                .content(registration.formatted("different-password", ",\"termsAccepted\":true"))).andExpect(status().isBadRequest());
        verifyNoInteractions(accounts);
    }

    @Test void loginRotatesSessionAndLogoutMakesItUnusable() throws Exception {
        UUID id = UUID.randomUUID();
        String email = "session-test@example.com";
        when(users.loadUserByUsername(email)).thenReturn(new AuthUser(id, email,
                encoder.encode("test-password"), "CUSTOMER", true));
        when(accounts.profile(id)).thenReturn(new UserView(id, email, "Test", "User", null, "CUSTOMER", Instant.now(), false, List.of(), false));
        MockHttpSession session = new MockHttpSession();
        String oldId = session.getId();
        mvc.perform(post("/api/v1/auth/login").servletPath("/api/v1/auth/login").session(session).with(csrf()).contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk());
        assertThat(session.getId()).isNotEqualTo(oldId);
        var context = (org.springframework.security.core.context.SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(((AuthUser) context.getAuthentication().getPrincipal()).getPassword()).isNull();
        mvc.perform(get("/api/v1/auth/me").servletPath("/api/v1/auth/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").servletPath("/api/v1/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent()).andExpect(header().stringValues("Set-Cookie",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("OLVENIO_SESSION=;"))));
        assertThat(session.isInvalid()).isTrue();
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/v1/auth/me").servletPath("/api/v1/auth/me").cookie(new jakarta.servlet.http.Cookie("OLVENIO_SESSION", oldId)))
                .andExpect(status().isUnauthorized());
    }

    @Test void corsDoesNotReflectUnconfiguredOrigins() throws Exception {
        mvc.perform(options("/api/v1/carts").servletPath("/api/v1/carts").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/api/v1/carts").servletPath("/api/v1/carts").header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "X-XSRF-TOKEN"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test void accountLoginLimitSurvivesSourceIpRotation() throws Exception {
        String email = "rate-test@example.com";
        when(users.loadUserByUsername(email)).thenThrow(new org.springframework.security.core.userdetails.UsernameNotFoundException("Not found"));
        for (int i = 0; i < 11; i++) {
            final int source = i;
            mvc.perform(post("/api/v1/auth/login").servletPath("/api/v1/auth/login").with(csrf())
                    .with(req -> { req.setRemoteAddr("192.0.2." + source); return req; })
                    .contentType("application/json").content("{\"email\":\"" + email + "\",\"password\":\"incorrect-password\"}"))
                    .andExpect(status().is(i < 10 ? 401 : 429));
        }
        verify(users, times(10)).loadUserByUsername(email);
    }

    @Test void bcryptByteLimitRejectsLongUnicodePasswordBeforeHashing() throws Exception {
        mvc.perform(post("/api/v1/auth/login").servletPath("/api/v1/auth/login").with(csrf()).contentType("application/json")
                .content("{\"email\":\"unicode-test@example.com\",\"password\":\"" + "é".repeat(50) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(users);
    }
}
