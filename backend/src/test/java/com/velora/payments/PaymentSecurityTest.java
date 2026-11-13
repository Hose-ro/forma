package com.velora.payments;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.velora.payments.api.*;
import com.velora.payments.application.*;
import com.velora.payments.infrastructure.*;
import com.velora.shared.config.*;
import com.velora.shared.security.GuestSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({PaymentController.class,MercadoPagoWebhookController.class})
@Import({SecurityConfig.class,PaymentSecurityTest.Props.class})
class PaymentSecurityTest {
    @TestConfiguration @EnableConfigurationProperties(AppProperties.class) static class Props {}
    @Autowired MockMvc mvc;
    @MockitoBean PaymentService service;
    @MockitoBean PaymentRepository payments;
    @MockitoBean GuestSession guest;
    @MockitoBean PaymentWebhookService webhook;
    @MockitoBean UserDetailsService users;
    @Test void onlyWebhookIsExemptFromCsrf() throws Exception {
        mvc.perform(post("/api/v1/webhooks/mercadopago").param("data.id","123").param("type","payment"))
            .andExpect(status().isOk());
        verify(webhook).receive(null,null,"123","payment");
        mvc.perform(post("/api/v1/payments/mercadopago/preference").contentType("application/json")
            .header("Idempotency-Key","payment-key-12345678").content("{\"orderId\":\"11111111-1111-1111-1111-111111111111\"}"))
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
