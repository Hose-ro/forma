package com.velora.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velora.payments.infrastructure.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Opt-in read-only verification against an existing preference; never creates a charge or preference. */
@EnabledIfEnvironmentVariable(named="MERCADOPAGO_SANDBOX_CHECK",matches="true")
class MercadoPagoSandboxIntegrationTest {
    @Test void existingPreferenceUsesOfficialCheckoutWithVerifiedMexicanTestSeller() {
        var config=new PaymentProperties(System.getenv("MERCADOPAGO_ACCESS_TOKEN"),System.getenv("MERCADOPAGO_WEBHOOK_SECRET"),
            "test",System.getenv("MERCADOPAGO_COLLECTOR_ID"),System.getenv("APP_PUBLIC_FRONTEND_URL"),System.getenv("APP_PUBLIC_BACKEND_URL"));
        var client=new MercadoPagoClient(config,new ObjectMapper());
        client.requireTestSeller();
        String id=System.getenv("MERCADOPAGO_TEST_PREFERENCE_ID");
        var preference=client.preference(id);
        assertThat(preference.path("id").asText()).isEqualTo(id);
        assertThat(preference.path("collector_id").asText()).isEqualTo(config.collectorId());
        assertThat(client.checkoutUrl(preference)).startsWith("https://www.mercadopago.com.mx/checkout/v1/redirect?");
    }
    @Test
    @EnabledIfEnvironmentVariable(named="MERCADOPAGO_TEST_PAYMENT_ID",matches="[0-9]+")
    void approvedExistingPaymentRequiresVerifiedTestPartiesEvenWithLiveModeTrue() {
        var config=new PaymentProperties(System.getenv("MERCADOPAGO_ACCESS_TOKEN"),System.getenv("MERCADOPAGO_WEBHOOK_SECRET"),
            "test",System.getenv("MERCADOPAGO_COLLECTOR_ID"),System.getenv("APP_PUBLIC_FRONTEND_URL"),System.getenv("APP_PUBLIC_BACKEND_URL"));
        var client=new MercadoPagoClient(config,new ObjectMapper());
        var resource=client.payment(System.getenv("MERCADOPAGO_TEST_PAYMENT_ID"));
        var expected=new com.velora.payments.domain.Payment(java.util.UUID.fromString(System.getenv("MERCADOPAGO_TEST_PAYMENT_REFERENCE")),java.util.UUID.randomUUID(),
            "MERCADO_PAGO",null,null,com.velora.payments.domain.PaymentStatus.PENDING,new java.math.BigDecimal(System.getenv("MERCADOPAGO_TEST_PAYMENT_AMOUNT")),"MXN",null,java.time.Instant.now(),null,null);
        boolean testAccounts=client.testAccountsVerified(resource);
        assertThat(testAccounts).isTrue();
        assertThat(resource.path("status").asText()).isEqualTo("approved");
        assertThat(com.velora.payments.application.PaymentVerificationService.verified(resource,expected,config.collectorId(),testAccounts)).isTrue();
    }

}
