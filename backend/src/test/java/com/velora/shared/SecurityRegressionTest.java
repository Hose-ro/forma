package com.velora.shared;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.velora.cart.api.CartController;
import com.velora.cart.application.CartService;
import com.velora.checkout.api.CheckoutController;
import com.velora.checkout.application.CheckoutService;
import com.velora.identity.api.AuthController;
import com.velora.identity.application.AccountService;
import com.velora.identity.application.IdentityDtos.UserView;
import com.velora.shared.config.*;
import com.velora.shared.security.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({AuthController.class, CartController.class, CheckoutController.class})
@Import({SecurityConfig.class, SecurityRegressionTest.Props.class})
class SecurityRegressionTest {
    @TestConfiguration @EnableConfigurationProperties(AppProperties.class) static class Props {}
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;
    @MockitoBean AccountService accounts;
    @MockitoBean UserDetailsService users;
    @MockitoBean CartService carts;
    @MockitoBean CheckoutService checkout;
    @MockitoBean GuestSession guest;

    @Test void privateEndpointRequiresAuthenticationAndIsNeverCached() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void loginAndCartMutationsRequireCsrf() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"email\":\"test@example.com\",\"password\":\"test-password\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/carts")).andExpect(status().isForbidden());
        verifyNoInteractions(accounts, carts, users);
    }

    @Test void rejectsPriceAndPaymentStatusMassAssignment() throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/orders").with(csrf()).contentType("application/json")
                .header("Idempotency-Key", "checkout-test-key")
                .content("{\"quoteId\":\"" + id + "\",\"total\":0,\"paymentStatus\":\"APPROVED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Invalid JSON request"));
        mvc.perform(post("/api/v1/carts/" + id + "/items").with(csrf()).contentType("application/json")
                .content("{\"variantId\":\"" + id + "\",\"quantity\":1,\"price\":0}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(carts, checkout);
    }

    @Test void rejectsRoleInjectionInvalidQuantityAndOversizedJson() throws Exception {
        mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType("application/json")
                .content("{\"email\":\"test@example.com\",\"password\":\"test-password\",\"firstName\":\"Test\",\"lastName\":\"User\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/carts/" + UUID.randomUUID() + "/items").with(csrf()).contentType("application/json")
                .content("{\"variantId\":\"" + UUID.randomUUID() + "\",\"quantity\":100}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/carts").with(csrf()).contentType("application/json").content("x".repeat(131073)))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(accounts, carts);
    }

    @Test void loginRotatesSessionAndLogoutMakesItUnusable() throws Exception {
        UUID id = UUID.randomUUID();
        String email = "session-test@example.com";
        when(users.loadUserByUsername(email)).thenReturn(new AuthUser(id, email,
                encoder.encode("test-password"), "CUSTOMER", true));
        when(accounts.profile(id)).thenReturn(new UserView(id, email, "Test", "User", null, "CUSTOMER", Instant.now()));
        MockHttpSession session = new MockHttpSession();
        String oldId = session.getId();
        mvc.perform(post("/api/v1/auth/login").session(session).with(csrf()).contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk());
        assertThat(session.getId()).isNotEqualTo(oldId);
        var context = (org.springframework.security.core.context.SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(((AuthUser) context.getAuthentication().getPrincipal()).getPassword()).isNull();
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent()).andExpect(header().stringValues("Set-Cookie",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("VELORA_SESSION=;"))));
        assertThat(session.isInvalid()).isTrue();
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/v1/auth/me").cookie(new jakarta.servlet.http.Cookie("VELORA_SESSION", oldId)))
                .andExpect(status().isUnauthorized());
    }

    @Test void corsDoesNotReflectUnconfiguredOrigins() throws Exception {
        mvc.perform(options("/api/v1/carts").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/api/v1/carts").header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "X-XSRF-TOKEN"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test void accountLoginLimitSurvivesSourceIpRotation() throws Exception {
        String email = "rate-test@example.com";
        when(users.loadUserByUsername(email)).thenThrow(new org.springframework.security.core.userdetails.UsernameNotFoundException("Not found"));
        for (int i = 0; i < 11; i++) {
            final int source = i;
            mvc.perform(post("/api/v1/auth/login").with(csrf())
                    .with(req -> { req.setRemoteAddr("192.0.2." + source); return req; })
                    .contentType("application/json").content("{\"email\":\"" + email + "\",\"password\":\"incorrect-password\"}"))
                    .andExpect(status().is(i < 10 ? 401 : 429));
        }
        verify(users, times(10)).loadUserByUsername(email);
    }

    @Test void bcryptByteLimitRejectsLongUnicodePasswordBeforeHashing() throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                .content("{\"email\":\"unicode-test@example.com\",\"password\":\"" + "é".repeat(50) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(users);
    }
}
