package com.olvenio.payments;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.olvenio.payments.application.*;
import com.olvenio.payments.application.port.ProviderCharge;
import com.olvenio.payments.domain.*;
import com.olvenio.payments.infrastructure.*;
import com.olvenio.payments.infrastructure.paypal.*;
import com.olvenio.shared.domain.ConflictException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PayPalGatewayTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PayPalClient client = mock(PayPalClient.class);
    private final PayPalProperties config = new PayPalProperties("client", "secret", "WH12345ABC", "test");
    private final PaymentProperties urls = new PaymentProperties(null, null, "test", null, "https://front.example", "https://api.example");
    private final PayPalGateway gateway = new PayPalGateway(client, config, urls);
    private final Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), "PAYPAL", null, "5O190127TN364715T",
            PaymentStatus.PENDING, new BigDecimal("1234.5000"), "MXN", null, Instant.now(), null, null);

    private ObjectNode order(String status) {
        ObjectNode o = json.createObjectNode();
        o.put("id", "5O190127TN364715T"); o.put("status", status);
        ObjectNode unit = o.putArray("purchase_units").addObject();
        unit.put("custom_id", payment.id().toString());
        unit.putObject("amount").put("currency_code", "MXN").put("value", "1234.50");
        o.putArray("links").addObject().put("rel", "payer-action").put("href", "https://www.sandbox.paypal.com/checkoutnow?token=5O190127TN364715T");
        return o;
    }
    private ObjectNode capture(ObjectNode order, String status) {
        ObjectNode payments = ((ObjectNode) order.path("purchase_units").path(0)).putObject("payments");
        ObjectNode c = payments.putArray("captures").addObject();
        c.put("id", "3C679366HH908993F"); c.put("status", status); c.put("custom_id", payment.id().toString());
        c.putObject("amount").put("currency_code", "MXN").put("value", "1234.50");
        c.put("update_time", "2026-11-17T10:00:00Z");
        return payments;
    }

    @Test @SuppressWarnings("unchecked") void createsIdempotentOrderAndOnlyTrustsPayPalHosts() {
        when(client.createOrder(any(), anyString())).thenReturn(order("PAYER_ACTION_REQUIRED"));
        var session = gateway.create(payment, Instant.now().plusSeconds(3600));
        assertThat(session.url()).startsWith("https://www.sandbox.paypal.com/checkoutnow");
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).createOrder(body.capture(), eq("olvenio-checkout-" + payment.id()));
        JsonNode sent = json.valueToTree(body.getValue());
        assertThat(sent.path("intent").asText()).isEqualTo("CAPTURE");
        assertThat(sent.path("purchase_units").path(0).path("custom_id").asText()).isEqualTo(payment.id().toString());
        assertThat(sent.path("purchase_units").path(0).path("amount").path("value").asText()).isEqualTo("1234.50");
        assertThat(sent.path("payment_source").path("paypal").path("experience_context").path("return_url").asText())
            .isEqualTo("https://front.example/checkout/paypal?orderId=" + payment.orderId() + "&paymentId=" + payment.id());

        for (var mutate : List.<java.util.function.Consumer<ObjectNode>>of(
                o -> ((ObjectNode) o.path("purchase_units").path(0).path("amount")).put("value", "1234.49"),
                o -> ((ObjectNode) o.path("purchase_units").path(0)).put("custom_id", UUID.randomUUID().toString()),
                o -> ((ObjectNode) o.path("links").path(0)).put("href", "https://www.paypal.com/checkoutnow?token=x"),
                o -> ((ObjectNode) o.path("links").path(0)).put("href", "https://www.sandbox.paypal.com.evil.example/x"),
                o -> ((ObjectNode) o.path("links").path(0)).put("rel", "self"))) {
            ObjectNode o = order("PAYER_ACTION_REQUIRED"); mutate.accept(o);
            when(client.createOrder(any(), anyString())).thenReturn(o);
            assertThatThrownBy(() -> gateway.create(payment, Instant.now().plusSeconds(3600))).isInstanceOf(ProviderUnavailableException.class);
        }
        var live = new PayPalGateway(client, new PayPalProperties("client", "secret", "WH12345ABC", "production"), urls);
        assertThat(live.allowedCheckoutUrl("https://www.paypal.com/checkoutnow?token=1")).isTrue();
        assertThat(live.allowedCheckoutUrl("https://www.sandbox.paypal.com/checkoutnow?token=1")).isFalse();
    }

    @Test void captureIsIdempotentAndReportsBuyerProblemsAsConflicts() {
        var ok = new PayPalClient.Response(201, json.createObjectNode());
        when(client.capture(anyString(), anyString())).thenReturn(ok);
        gateway.complete(payment);
        verify(client).capture("5O190127TN364715T", "olvenio-capture-" + payment.id());
        when(client.capture(anyString(), anyString())).thenReturn(issue(422, "ORDER_ALREADY_CAPTURED"));
        gateway.complete(payment);
        when(client.capture(anyString(), anyString())).thenReturn(issue(422, "ORDER_NOT_APPROVED"));
        assertThatThrownBy(() -> gateway.complete(payment)).isInstanceOf(ConflictException.class);
        when(client.capture(anyString(), anyString())).thenReturn(issue(422, "INSTRUMENT_DECLINED"));
        assertThatThrownBy(() -> gateway.complete(payment)).isInstanceOf(ConflictException.class);
        when(client.capture(anyString(), anyString())).thenReturn(issue(400, "SOMETHING_NEW"));
        assertThatThrownBy(() -> gateway.complete(payment)).isInstanceOf(ProviderUnavailableException.class);
    }
    private PayPalClient.Response issue(int status, String issue) {
        ObjectNode body = json.createObjectNode();
        body.putArray("details").addObject().put("issue", issue);
        return new PayPalClient.Response(status, body);
    }

    @Test void mapsCapturesAndRefunds() {
        ObjectNode o = order("COMPLETED");
        ObjectNode payments = capture(o, "COMPLETED");
        List<ProviderCharge> seen = new ArrayList<>();
        when(client.order("5O190127TN364715T")).thenReturn(o);
        gateway.forEachCharge(payment, null, seen::add);
        assertThat(seen).hasSize(1);
        ProviderCharge c = seen.getFirst();
        assertThat(c.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(c.chargeId()).isEqualTo("3C679366HH908993F");
        assertThat(c.updatedAt()).isEqualTo(Instant.parse("2026-11-17T10:00:00Z"));
        assertThat(PaymentVerificationService.matches(c, payment)).isTrue();

        payments.putArray("refunds").addObject().put("status", "COMPLETED").putObject("amount").put("value", "100.00");
        assertThat(charge(o).status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        ((com.fasterxml.jackson.databind.node.ArrayNode) payments.path("refunds")).addObject().put("status", "COMPLETED").putObject("amount").put("value", "1134.50");
        assertThat(charge(o).status()).isEqualTo(PaymentStatus.REFUNDED);
        ((com.fasterxml.jackson.databind.node.ArrayNode) payments.path("refunds")).removeAll();
        for (var e : Map.of("PENDING", PaymentStatus.PENDING, "DECLINED", PaymentStatus.REJECTED, "FAILED", PaymentStatus.REJECTED,
                "REVERSED", PaymentStatus.UNKNOWN, "REFUNDED", PaymentStatus.REFUNDED).entrySet()) {
            ((ObjectNode) payments.path("captures").path(0)).put("status", e.getKey());
            assertThat(charge(o).status()).as(e.getKey()).isEqualTo(e.getValue());
        }
        ((ObjectNode) payments.path("captures").path(0)).put("status", "COMPLETED");
        ((ObjectNode) payments.path("captures").path(0).path("amount")).put("value", "1000.00");
        assertThat(PaymentVerificationService.matches(charge(o), payment)).isFalse();
    }
    private ProviderCharge charge(ObjectNode o) {
        return PayPalGateway.toCharge(o.path("purchase_units").path(0).path("payments").path("captures").path(0),
                o.path("purchase_units").path(0), o.path("purchase_units").path(0).path("payments").path("refunds"), Instant.now(), false);
    }

    @Test void noOrderIdMeansNoChargeAndCloseNeedsNoProviderCall() {
        var uncertain = new Payment(payment.id(), payment.orderId(), "PAYPAL", null, null, PaymentStatus.UNKNOWN, payment.amount(), "MXN", null, Instant.now(), null, null);
        List<ProviderCharge> seen = new ArrayList<>();
        gateway.forEachCharge(uncertain, null, seen::add);
        gateway.close(payment);
        assertThat(seen).isEmpty();
        verifyNoInteractions(client);
    }

    @Test void webhookIsVerifiedByPayPalBeforeAnythingHappens() throws Exception {
        var payments = mock(PaymentRepository.class);
        var service = mock(PaymentService.class);
        var verification = mock(PaymentVerificationService.class);
        var gw = mock(PayPalGateway.class);
        var webhook = new PayPalWebhookService(config, client, gw, payments, service, verification, json);
        var headers = new PayPalWebhookService.Transmission("SHA256withRSA", "https://api.paypal.com/cert", "tx-1", "sig", "2026-11-17T10:00:00Z");
        ObjectNode event = json.createObjectNode();
        event.put("event_type", "CHECKOUT.ORDER.APPROVED");
        event.set("resource", order("APPROVED"));
        byte[] body = json.writeValueAsBytes(event);

        assertThatThrownBy(() -> webhook.receive(new PayPalWebhookService.Transmission(null, "c", "i", "s", "t"), body))
            .isInstanceOf(PaymentWebhookService.InvalidSignatureException.class);
        verifyNoInteractions(client);
        when(client.verifyWebhook(any())).thenReturn(json.readTree("{\"verification_status\":\"FAILURE\"}"));
        assertThatThrownBy(() -> webhook.receive(headers, body)).isInstanceOf(PaymentWebhookService.InvalidSignatureException.class);
        verifyNoInteractions(service, payments, gw);

        when(client.verifyWebhook(any())).thenReturn(json.readTree("{\"verification_status\":\"SUCCESS\"}"));
        when(payments.find(payment.id())).thenReturn(Optional.of(payment));
        webhook.receive(headers, body);
        verify(service).confirmFromProvider(payment.id());

        event.put("event_type", "PAYMENT.CAPTURE.REFUNDED");
        ObjectNode captureResource = json.createObjectNode();
        captureResource.put("id", "3C679366HH908993F"); captureResource.put("custom_id", payment.id().toString());
        event.set("resource", captureResource);
        webhook.receive(headers, json.writeValueAsBytes(event));
        verify(gw).forEachCharge(eq(payment), isNull(), any());
    }
}
