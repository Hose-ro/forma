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
import com.olvenio.payments.infrastructure.stripe.*;
import com.olvenio.shared.domain.ConflictException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StripeGatewayTest {
    private final ObjectMapper json = new ObjectMapper();
    private final StripeClient client = mock(StripeClient.class);
    private final StripeProperties config = new StripeProperties("sk_test_0123456789abcdef", "whsec_0123456789abcdef", "test");
    private final PaymentProperties urls = new PaymentProperties(null, null, "test", null, "https://front.example/", "https://api.example");
    private final StripeGateway gateway = new StripeGateway(client, config, urls);
    private final Instant now = Instant.now();
    private final Payment payment = new Payment(UUID.randomUUID(), UUID.randomUUID(), "STRIPE", null, "cs_test_abc",
            PaymentStatus.PENDING, new BigDecimal("1234.5000"), "MXN", null, now, null, null);

    private ObjectNode session(String status) {
        ObjectNode s = json.createObjectNode();
        s.put("id", "cs_test_abc"); s.put("status", status); s.put("client_reference_id", payment.id().toString());
        s.put("livemode", false); s.put("currency", "mxn"); s.put("amount_total", 123450);
        s.put("url", "https://checkout.stripe.com/c/pay/cs_test_abc#fid");
        s.putNull("payment_intent");
        return s;
    }
    private ObjectNode intent(String status) {
        ObjectNode pi = json.createObjectNode();
        pi.put("id", "pi_123abc"); pi.put("status", status); pi.put("amount", 123450); pi.put("currency", "mxn");
        pi.put("livemode", false); pi.putObject("metadata").put("payment_id", payment.id().toString());
        return pi;
    }

    @Test @SuppressWarnings("unchecked") void createsDeterministicIdempotentSessionLimitedToTheReservation() {
        when(client.createSession(any(), anyString())).thenReturn(session("open"));
        Instant reservation = now.plus(Duration.ofHours(2));
        var created = gateway.create(payment, reservation);
        assertThat(created.id()).isEqualTo("cs_test_abc");
        ArgumentCaptor<Map<String, String>> form = ArgumentCaptor.forClass(Map.class);
        verify(client).createSession(form.capture(), eq("olvenio-checkout-" + payment.id()));
        assertThat(form.getValue()).containsEntry("mode", "payment").containsEntry("client_reference_id", payment.id().toString())
            .containsEntry("payment_intent_data[metadata][payment_id]", payment.id().toString())
            .containsEntry("line_items[0][price_data][unit_amount]", "123450").containsEntry("line_items[0][price_data][currency]", "mxn")
            .containsEntry("payment_method_types[1]", "oxxo").containsEntry("payment_method_options[oxxo][expires_after_days]", "1")
            .containsEntry("expires_at", String.valueOf(reservation.getEpochSecond()))
            .containsEntry("success_url", "https://front.example/checkout/success?orderId=" + payment.orderId());
        // A retry with the same reservation sends exactly the same request.
        gateway.create(payment, reservation);
        verify(client, times(2)).createSession(eq(form.getValue()), eq("olvenio-checkout-" + payment.id()));
    }

    @Test void sessionWindowStaysInsideStripeLimits() {
        var p = new Payment(payment.id(), payment.orderId(), "STRIPE", null, null, PaymentStatus.CREATED, payment.amount(), "MXN", null,
                Instant.parse("2026-11-17T10:00:00.700Z"), null, null);
        assertThat(callExpires(p, Instant.parse("2026-11-18T12:00:00Z"))).isEqualTo(Instant.parse("2026-11-18T09:50:00Z"));
        assertThatThrownBy(() -> callExpires(p, Instant.parse("2026-11-17T10:20:00Z"))).isInstanceOf(ConflictException.class);
    }
    private Instant callExpires(Payment p, Instant reservation) {
        when(client.createSession(any(), anyString())).thenReturn(session("open"));
        ArgumentCaptor<Map<String, String>> form = ArgumentCaptor.captor();
        try { gateway.create(p, reservation); } catch (ProviderUnavailableException ignored) {}
        verify(client, atLeastOnce()).createSession(form.capture(), anyString());
        reset(client);
        return Instant.ofEpochSecond(Long.parseLong(form.getValue().get("expires_at")));
    }

    @Test void rejectsSessionsThatDoNotMatchThePayment() {
        for (var mutate : List.<java.util.function.Consumer<ObjectNode>>of(
                s -> s.put("amount_total", 123449), s -> s.put("client_reference_id", UUID.randomUUID().toString()),
                s -> s.put("livemode", true), s -> s.put("currency", "usd"),
                s -> s.put("url", "https://checkout.stripe.com.evil.example/c/pay/x"), s -> s.put("url", "http://checkout.stripe.com/c/pay/x"))) {
            ObjectNode s = session("open"); mutate.accept(s);
            when(client.createSession(any(), anyString())).thenReturn(s);
            assertThatThrownBy(() -> gateway.create(payment, now.plus(Duration.ofHours(2)))).isInstanceOf(ProviderUnavailableException.class);
        }
        assertThatThrownBy(() -> gateway.create(new Payment(payment.id(), payment.orderId(), "STRIPE", null, null, PaymentStatus.CREATED,
                new BigDecimal("10.005"), "MXN", null, now, null, null), now.plus(Duration.ofHours(2)))).isInstanceOf(ConflictException.class);
    }

    @Test void mapsIntentStatesRefundsAndDisputes() {
        Instant read = Instant.now();
        assertThat(StripeGateway.toCharge(intent("requires_action"), read, false).status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(StripeGateway.toCharge(intent("processing"), read, false).status()).isEqualTo(PaymentStatus.IN_PROCESS);
        assertThat(StripeGateway.toCharge(intent("requires_payment_method"), read, false).status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(StripeGateway.toCharge(intent("canceled"), read, false).status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(StripeGateway.toCharge(intent("weird_new_state"), read, false).status()).isEqualTo(PaymentStatus.UNKNOWN);
        ObjectNode paid = intent("succeeded");
        ObjectNode charge = paid.putObject("latest_charge"); charge.put("amount_refunded", 0); charge.put("disputed", false);
        ProviderCharge c = StripeGateway.toCharge(paid, read, false);
        assertThat(c.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(c.amount()).isEqualByComparingTo("1234.50");
        assertThat(c.currency()).isEqualTo("MXN");
        assertThat(c.reference()).isEqualTo(payment.id());
        assertThat(c.chargeId()).isEqualTo("pi_123abc");
        assertThat(c.updatedAt()).isEqualTo(read);
        assertThat(PaymentVerificationService.matches(c, payment)).isTrue();
        charge.put("amount_refunded", 1000);
        assertThat(StripeGateway.toCharge(paid, read, false).status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        charge.put("amount_refunded", 123450);
        assertThat(StripeGateway.toCharge(paid, read, false).status()).isEqualTo(PaymentStatus.REFUNDED);
        charge.put("amount_refunded", 0); charge.put("disputed", true);
        assertThat(StripeGateway.toCharge(paid, read, false).status()).isEqualTo(PaymentStatus.UNKNOWN);
        // Unexpanded charge: refunds unknown, so the snapshot cannot be trusted.
        paid.put("latest_charge", "ch_1");
        assertThat(PaymentVerificationService.matches(StripeGateway.toCharge(paid, read, false), payment)).isFalse();
    }

    @Test void environmentAndOwnershipAreVerified() {
        ObjectNode pi = intent("succeeded");
        pi.putObject("latest_charge").put("amount_refunded", 0);
        assertThat(PaymentVerificationService.matches(StripeGateway.toCharge(pi, now, false), payment)).isTrue();
        assertThat(PaymentVerificationService.matches(StripeGateway.toCharge(pi, now, true), payment)).isFalse();
        pi.put("livemode", true);
        assertThat(PaymentVerificationService.matches(StripeGateway.toCharge(pi, now, false), payment)).isFalse();
        pi.put("livemode", false); pi.put("amount", 123449);
        assertThat(PaymentVerificationService.matches(StripeGateway.toCharge(pi, now, false), payment)).isFalse();
        pi.put("amount", 123450); ((ObjectNode) pi.path("metadata")).put("payment_id", UUID.randomUUID().toString());
        assertThat(PaymentVerificationService.matches(StripeGateway.toCharge(pi, now, false), payment)).isFalse();
        var mp = new Payment(payment.id(), payment.orderId(), "MERCADO_PAGO", null, null, PaymentStatus.PENDING, payment.amount(), "MXN", null, now, null, null);
        ((ObjectNode) pi.path("metadata")).put("payment_id", payment.id().toString());
        assertThat(PaymentVerificationService.matches(StripeGateway.toCharge(pi, now, false), mp)).isFalse();
    }

    @Test void closeExpiresOpenSessionAndCancelsAnUnpaidVoucher() {
        ObjectNode open = session("open");
        ObjectNode expired = session("expired");
        expired.set("payment_intent", intent("requires_action"));
        when(client.session("cs_test_abc")).thenReturn(open, expired);
        gateway.close(payment);
        verify(client).expireSession("cs_test_abc");
        verify(client).cancelPaymentIntent("pi_123abc");

        reset(client);
        ObjectNode complete = session("complete");
        complete.set("payment_intent", intent("succeeded"));
        when(client.session("cs_test_abc")).thenReturn(complete);
        gateway.close(payment);
        verify(client, never()).expireSession(any());
        verify(client, never()).cancelPaymentIntent(any());
    }

    @Test void sessionStillOpenAfterExpireOrUnknownSessionKeepsTheCloseFailing() {
        when(client.session("cs_test_abc")).thenReturn(session("open"));
        assertThatThrownBy(() -> gateway.close(payment)).isInstanceOf(ProviderUnavailableException.class);
        var uncertain = new Payment(payment.id(), payment.orderId(), "STRIPE", null, null, PaymentStatus.UNKNOWN, payment.amount(), "MXN", null,
                now.minus(Duration.ofHours(2)), null, null);
        assertThatThrownBy(() -> gateway.close(uncertain)).isInstanceOf(ConflictException.class);
        var old = new Payment(payment.id(), payment.orderId(), "STRIPE", null, null, PaymentStatus.UNKNOWN, payment.amount(), "MXN", null,
                now.minus(Duration.ofHours(25)), null, null);
        gateway.close(old);
    }

    @Test void chargesComeFromTheSessionAndFromSearch() throws Exception {
        ObjectNode s = session("complete");
        s.set("payment_intent", intent("succeeded"));
        when(client.session("cs_test_abc")).thenReturn(s);
        when(client.searchPaymentIntents(payment.id(), null)).thenReturn(json.readTree("{\"data\":[{\"id\":\"pi_123abc\"},{\"id\":\"pi_other\"}],\"has_more\":false}"));
        when(client.paymentIntent(anyString())).thenAnswer(i -> { ObjectNode pi = intent("requires_payment_method"); pi.put("id", (String) i.getArgument(0)); return pi; });
        List<ProviderCharge> seen = new ArrayList<>();
        gateway.forEachCharge(payment, null, seen::add);
        assertThat(seen).extracting(ProviderCharge::chargeId).containsExactly("pi_123abc", "pi_other");
    }

    @Test void checkoutUrlAllowsOnlyStripeHostedPages() {
        assertThat(gateway.allowedCheckoutUrl("https://checkout.stripe.com/c/pay/cs_test_a1#fidkdWx")).isTrue();
        for (String bad : List.of("http://checkout.stripe.com/c/pay/x", "https://checkout.stripe.com.evil.example/c/pay/x",
                "https://user@checkout.stripe.com/c/pay/x", "https://checkout.stripe.com:8443/c/pay/x", "https://evil.example/", "not a url"))
            assertThat(gateway.allowedCheckoutUrl(bad)).as(bad).isFalse();
    }

    // --- Webhook ---

    private static String sign(String secret, long t, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
        return "t=" + t + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test void signatureRequiresSecretFreshTimestampAndExactBody() throws Exception {
        byte[] body = "{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);
        long t = now.getEpochSecond();
        String header = sign("whsec_0123456789abcdef", t, body);
        assertThat(StripeSignature.valid(header, body, "whsec_0123456789abcdef", now)).isTrue();
        assertThat(StripeSignature.valid("v0=abc," + header + ",v1=" + "0".repeat(64), body, "whsec_0123456789abcdef", now)).isTrue();
        assertThat(StripeSignature.valid(header, "{\"id\":\"evt_2\"}".getBytes(StandardCharsets.UTF_8), "whsec_0123456789abcdef", now)).isFalse();
        assertThat(StripeSignature.valid(header, body, "whsec_other_secret0", now)).isFalse();
        assertThat(StripeSignature.valid(header, body, "whsec_0123456789abcdef", now.plusSeconds(301))).isFalse();
        assertThat(StripeSignature.valid("t=" + t, body, "whsec_0123456789abcdef", now)).isFalse();
        assertThat(StripeSignature.valid(header + ",t=" + t, body, "whsec_0123456789abcdef", now)).isFalse();
        assertThat(StripeSignature.valid(null, body, "whsec_0123456789abcdef", now)).isFalse();
    }

    @Test void webhookReReadsTheReferencedPaymentAndIgnoresForgeries() throws Exception {
        var payments = mock(PaymentRepository.class);
        var verification = mock(PaymentVerificationService.class);
        var gw = mock(StripeGateway.class);
        var service = new StripeWebhookService(config, gw, payments, verification, json);
        byte[] forged = "{\"type\":\"checkout.session.completed\"}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.receive("t=1,v1=" + "a".repeat(64), forged)).isInstanceOf(PaymentWebhookService.InvalidSignatureException.class);
        verifyNoInteractions(gw, payments, verification);

        ObjectNode event = json.createObjectNode();
        event.put("type", "checkout.session.async_payment_succeeded"); event.put("livemode", false);
        event.putObject("data").set("object", session("complete").put("object", "checkout.session"));
        byte[] body = json.writeValueAsBytes(event);
        when(gw.referenceOf(any())).thenReturn(Optional.of(payment.id()));
        when(payments.find(payment.id())).thenReturn(Optional.of(payment));
        service.receive(sign("whsec_0123456789abcdef", Instant.now().getEpochSecond(), body), body);
        verify(gw).forEachCharge(eq(payment), isNull(), any());

        event.put("livemode", true);
        byte[] live = json.writeValueAsBytes(event);
        service.receive(sign("whsec_0123456789abcdef", Instant.now().getEpochSecond(), live), live);
        verify(gw, times(1)).forEachCharge(any(), any(), any());
    }
}
