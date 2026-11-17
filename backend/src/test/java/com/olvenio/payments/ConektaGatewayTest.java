package com.olvenio.payments;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.olvenio.payments.application.*;
import com.olvenio.payments.application.port.*;
import com.olvenio.payments.domain.*;
import com.olvenio.payments.infrastructure.*;
import com.olvenio.payments.infrastructure.conekta.*;
import com.olvenio.shared.domain.ConflictException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConektaGatewayTest {
    private static final KeyPair KEYS = keys();
    private final ObjectMapper json = new ObjectMapper();
    private final ConektaClient client = mock(ConektaClient.class);
    private final ConektaProperties config = new ConektaProperties("key_0123456789abcdef", pem(), "test", null);
    private final PaymentProperties urls = new PaymentProperties(null, null, "test", null, "https://front.example", "https://api.example");
    private final ConektaGateway gateway = new ConektaGateway(client, config, urls);
    private final Instant now = Instant.now();
    private final Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), "CONEKTA", null, "ord_2tUigJ8DgBhbp6w5T",
            PaymentStatus.PENDING, new BigDecimal("1234.5000"), "MXN", null, now, null, null);
    private final CheckoutBuyer buyer = new CheckoutBuyer("Ana Perez", "ana@example.com", null);

    private static KeyPair keys() {
        try { var g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String pem() {
        return "-----BEGIN PUBLIC KEY-----\\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(KEYS.getPublic().getEncoded())
                .replace("\n", "\\n") + "\\n-----END PUBLIC KEY-----";
    }
    private static String sign(byte[] body) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA"); s.initSign(KEYS.getPrivate()); s.update(body);
        return Base64.getEncoder().encodeToString(s.sign());
    }

    private ObjectNode order(String paymentStatus) {
        ObjectNode o = json.createObjectNode();
        o.put("id", "ord_2tUigJ8DgBhbp6w5T"); o.put("livemode", false); o.put("currency", "MXN"); o.put("amount", 123450);
        o.put("payment_status", paymentStatus); o.put("object", "order");
        o.putObject("metadata").put("payment_id", payment.id().toString());
        o.putObject("checkout").put("url", "https://pay.conekta.com/checkout/abc123");
        o.putObject("charges").putArray("data");
        return o;
    }
    private ObjectNode charge(ObjectNode order, String status) {
        ObjectNode c = ((ArrayNode) order.path("charges").path("data")).addObject();
        c.put("id", "6407b5bee1329a000175ba11"); c.put("status", status); c.put("amount", 123450); c.put("currency", "MXN");
        c.put("livemode", false); c.putObject("refunds").putArray("data");
        return c;
    }

    @Test @SuppressWarnings("unchecked") void createsIdempotentSpeiCheckoutWithBuyerAndMetadata() {
        when(client.createOrder(any(), anyString())).thenReturn(order("pending_payment"));
        Instant reservation = now.plusSeconds(7200);
        assertThat(gateway.create(payment, reservation, buyer).url()).isEqualTo("https://pay.conekta.com/checkout/abc123");
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).createOrder(body.capture(), eq("olvenio-checkout-" + payment.id()));
        JsonNode sent = json.valueToTree(body.getValue());
        assertThat(sent.path("line_items").path(0).path("unit_price").asLong()).isEqualTo(123450);
        assertThat(sent.path("metadata").path("payment_id").asText()).isEqualTo(payment.id().toString());
        assertThat(sent.path("customer_info").path("email").asText()).isEqualTo("ana@example.com");
        assertThat(sent.path("customer_info").has("phone")).isFalse();
        assertThat(sent.path("checkout").path("type").asText()).isEqualTo("HostedPayment");
        assertThat(sent.path("checkout").path("allowed_payment_methods").toString()).isEqualTo("[\"bank_transfer\"]");
        assertThat(sent.path("checkout").path("expires_at").asLong()).isEqualTo(reservation.getEpochSecond());
        assertThatThrownBy(() -> gateway.create(payment, now.plusSeconds(300), buyer)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> gateway.create(payment, reservation)).isInstanceOf(ConflictException.class);

        for (var mutate : List.<java.util.function.Consumer<ObjectNode>>of(o -> o.put("amount", 123449), o -> o.put("livemode", true),
                o -> ((ObjectNode) o.path("metadata")).put("payment_id", UUID.randomUUID().toString()),
                o -> ((ObjectNode) o.path("checkout")).put("url", "https://pay.conekta.com.evil.example/x"))) {
            ObjectNode o = order("pending_payment"); mutate.accept(o);
            when(client.createOrder(any(), anyString())).thenReturn(o);
            assertThatThrownBy(() -> gateway.create(payment, reservation, buyer)).isInstanceOf(ProviderUnavailableException.class);
        }
    }

    @Test void recoveryFindsTheOrderByPaymentIdInItsCreationWindow() {
        ObjectNode list = json.createObjectNode();
        ArrayNode data = list.putArray("data");
        ObjectNode other = order("pending_payment"); ((ObjectNode) other.path("metadata")).put("payment_id", UUID.randomUUID().toString());
        data.add(other); data.add(order("pending_payment"));
        when(client.ordersCreatedBetween(any(), any())).thenReturn(list);
        assertThat(gateway.findCreated(payment)).map(CheckoutSession::id).contains("ord_2tUigJ8DgBhbp6w5T");
        data.removeAll(); data.add(other);
        assertThat(gateway.findCreated(payment)).isEmpty();
    }

    @Test void closeCancelsPendingOrderButAPendingTransferKeepsItLive() {
        when(client.order("ord_2tUigJ8DgBhbp6w5T")).thenReturn(order("pending_payment"), order("canceled"));
        gateway.close(payment);
        verify(client).cancelOrder("ord_2tUigJ8DgBhbp6w5T");

        reset(client);
        ObjectNode stillPending = order("pending_payment");
        charge(stillPending, "pending_payment");
        when(client.order("ord_2tUigJ8DgBhbp6w5T")).thenReturn(order("pending_payment"), stillPending);
        when(client.cancelOrder(any())).thenReturn(new ConektaClient.Response(428, json.createObjectNode()));
        gateway.close(payment); // Closed for new charges; the live CLABE is reported by the charge re-read.
        List<ProviderCharge> seen = new ArrayList<>();
        when(client.order("ord_2tUigJ8DgBhbp6w5T")).thenReturn(stillPending);
        gateway.forEachCharge(payment, null, seen::add);
        assertThat(seen).extracting(ProviderCharge::status).containsExactly(PaymentStatus.PENDING);

        reset(client);
        when(client.order("ord_2tUigJ8DgBhbp6w5T")).thenReturn(order("pending_payment"));
        assertThatThrownBy(() -> gateway.close(payment)).isInstanceOf(ProviderUnavailableException.class);

        reset(client);
        when(client.order("ord_2tUigJ8DgBhbp6w5T")).thenReturn(order("paid"));
        gateway.close(payment);
        verify(client, never()).cancelOrder(any());
    }

    @Test void mapsChargesAndNegativeRefunds() {
        ObjectNode o = order("paid");
        ObjectNode c = charge(o, "paid");
        ProviderCharge paid = ConektaGateway.toCharge(c, o, now, false);
        assertThat(paid.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(paid.amount()).isEqualByComparingTo("1234.50");
        assertThat(PaymentVerificationService.matches(paid, payment)).isTrue();
        ((ArrayNode) c.path("refunds").path("data")).addObject().put("amount", -10000);
        assertThat(ConektaGateway.toCharge(c, o, now, false).status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        ((ArrayNode) c.path("refunds").path("data")).addObject().put("amount", -113450);
        assertThat(ConektaGateway.toCharge(c, o, now, false).status()).isEqualTo(PaymentStatus.REFUNDED);
        ((ArrayNode) c.path("refunds").path("data")).removeAll();
        for (var e : Map.of("pending_payment", PaymentStatus.PENDING, "declined", PaymentStatus.REJECTED, "expired", PaymentStatus.CANCELLED,
                "canceled", PaymentStatus.CANCELLED, "charged_back", PaymentStatus.UNKNOWN).entrySet()) {
            c.put("status", e.getKey());
            assertThat(ConektaGateway.toCharge(c, o, now, false).status()).as(e.getKey()).isEqualTo(e.getValue());
        }
        c.put("status", "paid");
        assertThat(PaymentVerificationService.matches(ConektaGateway.toCharge(c, o, now, true), payment)).isFalse();
        c.put("amount", 100000);
        assertThat(PaymentVerificationService.matches(ConektaGateway.toCharge(c, o, now, false), payment)).isFalse();
    }

    @Test void webhookDigestIsVerifiedWithTheConfiguredKey() throws Exception {
        var payments = mock(PaymentRepository.class);
        var verification = mock(PaymentVerificationService.class);
        var gw = mock(ConektaGateway.class);
        var service = new ConektaWebhookService(config, gw, payments, verification, json);
        ObjectNode event = json.createObjectNode();
        event.put("type", "order.paid"); event.put("livemode", false);
        event.putObject("data").set("object", order("paid"));
        byte[] body = json.writeValueAsBytes(event);

        assertThat(config.publicKey()).isNotNull();
        assertThat(ConektaSignature.valid(sign(body), body, config.publicKey())).isTrue();
        assertThat(ConektaSignature.valid(sign(body), "{}".getBytes(StandardCharsets.UTF_8), config.publicKey())).isFalse();
        assertThatThrownBy(() -> service.receive(sign("{}".getBytes(StandardCharsets.UTF_8)), body))
            .isInstanceOf(PaymentWebhookService.InvalidSignatureException.class);
        assertThatThrownBy(() -> service.receive(null, body)).isInstanceOf(PaymentWebhookService.InvalidSignatureException.class);
        verifyNoInteractions(gw, payments, verification);

        when(gw.referenceOf(any())).thenReturn(Optional.of(payment.id()));
        when(payments.find(payment.id())).thenReturn(Optional.of(payment));
        service.receive(sign(body), body);
        verify(gw).forEachCharge(eq(payment), isNull(), any());
    }

    @Test void configurationRequiresKeyFormatKnownMethodsAndAValidPublicKey() {
        config.requireConfigured();
        assertThatThrownBy(() -> new ConektaProperties("sk_test_x", pem(), "test", null).requireConfigured()).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> new ConektaProperties("key_0123456789abcdef", "not a key", "test", null).requireConfigured()).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> new ConektaProperties("key_0123456789abcdef", pem(), "test", List.of("crypto")).requireConfigured()).isInstanceOf(ConflictException.class);
    }
}
