package com.olvenio.payments;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.olvenio.payments.api.*;
import com.olvenio.payments.application.*;
import com.olvenio.payments.infrastructure.*;
import com.olvenio.shared.config.*;
import com.olvenio.shared.security.GuestSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({PaymentController.class,MercadoPagoWebhookController.class,StripeWebhookController.class,PayPalWebhookController.class,ConektaWebhookController.class})
@Import({SecurityConfig.class,PaymentSecurityTest.Props.class})
class PaymentSecurityTest {
    @TestConfiguration @EnableConfigurationProperties(AppProperties.class) static class Props {}
    @Autowired MockMvc mvc;
    @MockitoBean PaymentService service;
    @MockitoBean PaymentRepository payments;
    @MockitoBean GuestSession guest;
    @MockitoBean PaymentWebhookService webhook;
    @MockitoBean UserDetailsService users;
    @MockitoBean PaymentGateways gateways;
    @MockitoBean StripeWebhookService stripeWebhook;
    @MockitoBean PayPalWebhookService paypalWebhook;
    @MockitoBean ConektaWebhookService conektaWebhook;
    @Test void onlyWebhookIsExemptFromCsrf() throws Exception {
        mvc.perform(post("/api/v1/webhooks/mercadopago").param("data.id","123").param("type","payment"))
            .andExpect(status().isOk());
        verify(webhook).receive(null,null,"123","payment");
        mvc.perform(post("/api/v1/webhooks/stripe").contentType("application/json").header("Stripe-Signature","t=1,v1=00").content("{}"))
            .andExpect(status().isOk());
        verify(stripeWebhook).receive(eq("t=1,v1=00"),any());
        mvc.perform(post("/api/v1/webhooks/paypal").contentType("application/json").content("{}")).andExpect(status().isOk());
        verify(paypalWebhook).receive(any(),any());
        mvc.perform(post("/api/v1/webhooks/conekta").contentType("application/json").header("Digest","abc").content("{}")).andExpect(status().isOk());
        verify(conektaWebhook).receive(eq("abc"),any());
        mvc.perform(post("/api/v1/payments/11111111-1111-1111-1111-111111111111/confirm")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/payments/mercadopago/preference").contentType("application/json")
            .header("Idempotency-Key","payment-key-12345678").content("{\"orderId\":\"11111111-1111-1111-1111-111111111111\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/payments/checkout").contentType("application/json")
            .header("Idempotency-Key","payment-key-12345678").content("{\"orderId\":\"11111111-1111-1111-1111-111111111111\",\"provider\":\"STRIPE\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void invalidSignatureReturns401AndAdminPaymentsRequireAuthentication() throws Exception {
        doThrow(new PaymentWebhookService.InvalidSignatureException()).when(webhook).receive(null,null,"123","payment");
        mvc.perform(post("/api/v1/webhooks/mercadopago").param("data.id","123").param("type","payment"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/payments")).andExpect(status().isUnauthorized());
        verifyNoInteractions(payments);
    }
}
