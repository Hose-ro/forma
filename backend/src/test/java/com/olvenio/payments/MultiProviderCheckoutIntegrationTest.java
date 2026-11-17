package com.olvenio.payments;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olvenio.orders.domain.Order;
import com.olvenio.orders.domain.OrderStatus;
import com.olvenio.orders.infrastructure.OrderRepository;
import com.olvenio.payments.application.*;
import com.olvenio.payments.application.port.*;
import com.olvenio.payments.domain.*;
import com.olvenio.payments.infrastructure.*;
import com.olvenio.shared.domain.ConflictException;
import com.olvenio.shared.domain.Money;
import com.olvenio.shared.security.SecretTokens;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in PostgreSQL regression for switching payment method on a pending order. Runs every migration on an
 * empty, disposable database: OLVENIO_PAYMENTS_DB=true DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/olvenio
 */
@SpringBootTest(properties={"app.admin.email=","app.admin.password=","app.payments.access-token=test-token",
        "app.payments.webhook-secret=test-secret","app.payments.environment=test","app.payments.collector-id=123",
        "app.payments.frontend-url=https://front.example","app.payments.backend-url=https://api.example",
        "app.payments.reconciliation-delay=3600000"})
@EnabledIfEnvironmentVariable(named="OLVENIO_PAYMENTS_DB", matches="true")
class MultiProviderCheckoutIntegrationTest {
    static final String MP_URL="https://www.mercadopago.com.mx/checkout/v1/redirect?pref_id=";

    /** Stands in for a second provider; records closes and can be told to fail. */
    static class FakeGateway implements PaymentGateway {
        final AtomicInteger closes=new AtomicInteger();
        volatile boolean failClose;
        public PaymentProvider provider() { return PaymentProvider.STRIPE; }
        public boolean enabled() { return true; }
        public void requireConfigured() {}
        public void requireReady() {}
        public CheckoutSession create(Payment p,Instant expiresAt) { return new CheckoutSession("cs_"+p.id(),"https://checkout.example/"+p.id()); }
        public Optional<CheckoutSession> findCreated(Payment p) { return Optional.empty(); }
        public CheckoutSession refresh(Payment p) { return new CheckoutSession(p.providerPreferenceId(),p.checkoutUrl()); }
        public boolean allowedCheckoutUrl(String url) { return url.startsWith("https://checkout.example/"); }
        public void close(Payment p) { if (failClose) throw new ProviderUnavailableException(); closes.incrementAndGet(); }
        public void forEachCharge(Payment p,Instant deadline,Consumer<ProviderCharge> sink) {}
    }
    @TestConfiguration static class Fakes { @Bean FakeGateway fakeGateway() { return new FakeGateway(); } }

    @Autowired PaymentService service;
    @Autowired PaymentVerificationService verification;
    @Autowired ReservationService reservations;
    @Autowired OrderRepository orders;
    @Autowired SecretTokens tokens;
    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate tx;
    @Autowired FakeGateway stripe;
    @MockitoBean MercadoPagoClient client;
    private final ObjectMapper json=new ObjectMapper();

    @BeforeEach void provider() throws Exception {
        stripe.failClose=false;
        stripe.closes.set(0);
        when(client.createPreference(any(),any())).thenAnswer(i->{
            Payment p=i.getArgument(0);
            var n=json.createObjectNode();n.put("id","pref-"+p.id());n.put("collector_id","123");n.put("init_point",MP_URL+p.id());
            return n;
        });
        when(client.checkoutUrl(any())).thenAnswer(i->((com.fasterxml.jackson.databind.JsonNode)i.getArgument(0)).path("init_point").asText());
        when(client.search(any(),anyInt())).thenReturn(json.readTree("{\"results\":[]}"));
        when(client.expirePreference(any())).thenReturn(json.createObjectNode());
    }

    record Placed(UUID id,String token) {}
    private Placed order() {
        String token=tokens.newToken();
        UUID id=tx.execute(t->{
            Order o=Order.quote(null,null,"buyer@example.com","Ana","Perez",null,"MXN",tokens.hash(token),Instant.now().plusSeconds(600));
            var price=Money.of(new BigDecimal("100.00"),"MXN");
            o.addItem(null,null,"SKU-1","Producto","producto","Uno",null,price,1,price);
            o.applyTotals(price,Money.of(BigDecimal.ZERO,"MXN"),price);
            o.place("OLV-"+UUID.randomUUID().toString().substring(0,8),"order-key-"+UUID.randomUUID(),Instant.now(),null);
            o.reserveUntil(Instant.now().plus(1,ChronoUnit.HOURS));
            return orders.saveAndFlush(o).getId();
        });
        return new Placed(id,token);
    }
    private static String key() { return "pay-"+UUID.randomUUID(); }
    private List<Map<String,Object>> attempts(UUID order) {
        return db.queryForList("select provider,checkout_closed_at,provider_closed_at,close_reason,status from payments where order_id=? order by created_at",order);
    }
    private ProviderCharge charge(Payment p,String chargeId,PaymentStatus status) {
        return new ProviderCharge(PaymentProvider.valueOf(p.provider()),p.id(),chargeId,status.name().toLowerCase(),status,
                p.amount(),"MXN",BigDecimal.ZERO,Instant.now(),true,false,false);
    }
    /** Charge ids are global at a provider; keep them unique across runs on the same database. */
    private static String id(String prefix) { return prefix+Math.abs(UUID.randomUUID().getMostSignificantBits()%1_000_000_000L); }
    private OrderStatus status(UUID order) { return orders.findById(order).orElseThrow().getStatus(); }

    @Test void switchingClosesPreviousCheckoutAtItsProviderBeforeOpeningTheNextOne() {
        Placed o=order();
        Payment mp=service.checkout(o.id(),PaymentProvider.MERCADO_PAGO,key(),o.token());
        assertThat(service.checkout(o.id(),PaymentProvider.MERCADO_PAGO,key(),o.token()).id()).isEqualTo(mp.id());

        Payment card=service.checkout(o.id(),PaymentProvider.STRIPE,key(),o.token());

        verify(client).expirePreference("pref-"+mp.id());
        assertThat(card.provider()).isEqualTo("STRIPE");
        assertThat(card.checkoutUrl()).isEqualTo("https://checkout.example/"+card.id());
        var rows=attempts(o.id());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("close_reason")).isEqualTo("SWITCH");
        assertThat(rows.get(0).get("provider_closed_at")).isNotNull();
        assertThat(rows.get(1).get("checkout_closed_at")).isNull();
        assertThat(service.get(o.id(),o.token()).id()).isEqualTo(card.id());

        // Back to Mercado Pago: a new attempt, the card checkout is closed at its provider first.
        Payment again=service.checkout(o.id(),PaymentProvider.MERCADO_PAGO,key(),o.token());
        assertThat(again.id()).isNotIn(mp.id(),card.id());
        assertThat(stripe.closes.get()).isEqualTo(1);
        assertThatThrownBy(()->db.update("update payments set checkout_closed_at=null,close_reason=null where id=?",mp.id()))
            .hasMessageContaining("ux_payments_open_checkout");
    }

    @Test void failedProviderCloseKeepsTheNextMethodClosedUntilTheCloseSucceeds() {
        Placed o=order();
        Payment card=service.checkout(o.id(),PaymentProvider.STRIPE,key(),o.token());
        stripe.failClose=true;
        assertThatThrownBy(()->service.checkout(o.id(),PaymentProvider.MERCADO_PAGO,key(),o.token()))
            .isInstanceOf(ProviderUnavailableException.class);
        assertThat(attempts(o.id())).hasSize(1);
        verify(client,never()).createPreference(any(),any());
        // Even asking for the original method cannot reopen a checkout that may still be payable.
        assertThatThrownBy(()->service.checkout(o.id(),PaymentProvider.STRIPE,key(),o.token()))
            .isInstanceOf(ProviderUnavailableException.class);

        stripe.failClose=false;
        Payment mp=service.checkout(o.id(),PaymentProvider.MERCADO_PAGO,key(),o.token());
        assertThat(mp.provider()).isEqualTo("MERCADO_PAGO");
        assertThat(attempts(o.id()).get(0).get("provider_closed_at")).isNotNull();
        assertThat(card.id()).isNotEqualTo(mp.id());
    }

    @Test void pendingChargeOnPreviousMethodBlocksOpeningAnother() {
        Placed o=order();
        Payment mp=service.checkout(o.id(),PaymentProvider.MERCADO_PAGO,key(),o.token());
        String ticket=id("");
        verification.apply(charge(mp,ticket,PaymentStatus.PENDING)); // e.g. an unpaid cash ticket
        assertThatThrownBy(()->service.checkout(o.id(),PaymentProvider.STRIPE,key(),o.token()))
            .isInstanceOf(ConflictException.class).hasMessageContaining("still in process");
        assertThat(attempts(o.id())).hasSize(1);

        verification.apply(new ProviderCharge(PaymentProvider.MERCADO_PAGO,mp.id(),ticket,"cancelled",PaymentStatus.CANCELLED,
                mp.amount(),"MXN",BigDecimal.ZERO,Instant.now().plusSeconds(1),true,false,false));
        assertThat(service.checkout(o.id(),PaymentProvider.STRIPE,key(),o.token()).provider()).isEqualTo("STRIPE");
    }

    @Test void approvalOnTwoMethodsSendsTheOrderToReview() {
        Placed o=order();
        Payment mp=service.checkout(o.id(),PaymentProvider.MERCADO_PAGO,key(),o.token());
        Payment card=service.checkout(o.id(),PaymentProvider.STRIPE,key(),o.token());

        // The ticket of the closed Mercado Pago checkout gets paid anyway.
        verification.apply(charge(mp,id(""),PaymentStatus.APPROVED));
        assertThat(status(o.id())).isEqualTo(OrderStatus.PAID);
        assertThat(attempts(o.id()).get(1).get("close_reason")).isEqualTo("ORDER");
        // A rejected card afterwards must not reopen a paid order.
        verification.apply(charge(card,id("pi_"),PaymentStatus.REJECTED));
        assertThat(status(o.id())).isEqualTo(OrderStatus.PAID);

        verification.apply(charge(card,id("pi_"),PaymentStatus.APPROVED));
        assertThat(status(o.id())).isEqualTo(OrderStatus.PAYMENT_REVIEW);
        assertThat(db.queryForObject("select count(*) from payment_events where payment_id=? and event_type='DUPLICATE_CHARGE'",Integer.class,card.id())).isEqualTo(1);
        assertThatThrownBy(()->service.checkout(o.id(),PaymentProvider.MERCADO_PAGO,key(),o.token())).isInstanceOf(ConflictException.class);
    }

    @Test void cancellationClosesEveryAttemptAndBlocksNewCheckouts() {
        Placed o=order();
        service.checkout(o.id(),PaymentProvider.MERCADO_PAGO,key(),o.token());
        service.checkout(o.id(),PaymentProvider.STRIPE,key(),o.token());
        reservations.closeAtProvider(o.id());
        assertThat(attempts(o.id())).allSatisfy(r->assertThat(r.get("close_reason")).isEqualTo("ORDER"));
        assertThatThrownBy(()->service.checkout(o.id(),PaymentProvider.STRIPE,key(),o.token()))
            .isInstanceOf(ConflictException.class).hasMessageContaining("closing or closed");
        tx.executeWithoutResult(t->assertThat(reservations.release(o.id(),null,"test")).isEqualTo(ReservationService.Outcome.RELEASED));
        assertThat(status(o.id())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(attempts(o.id())).allSatisfy(r->assertThat(r.get("status")).isEqualTo("CANCELLED"));
    }
}
