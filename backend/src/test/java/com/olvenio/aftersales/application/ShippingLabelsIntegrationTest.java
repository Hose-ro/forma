package com.olvenio.aftersales.application;

import com.olvenio.aftersales.infrastructure.EnviaClient;
import com.olvenio.identity.domain.Role;
import com.olvenio.identity.domain.User;
import com.olvenio.identity.infrastructure.UserRepository;
import com.olvenio.orders.domain.Order;
import com.olvenio.orders.infrastructure.OrderRepository;
import com.olvenio.shared.domain.ConflictException;
import com.olvenio.shared.domain.Money;
import com.olvenio.shared.security.AuthUser;
import com.olvenio.shared.security.SecretTokens;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClientException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runs in a disposable olvenio_audit_* PostgreSQL database with Envia represented by a fake. */
@SpringBootTest(properties = {"app.admin.email=", "app.admin.password=", "app.payments.access-token="})
@EnabledIfEnvironmentVariable(named = "OLVENIO_AFTER_SALES_DB", matches = "true")
class ShippingLabelsIntegrationTest {
    @Autowired ShippingLabels labels;
    @Autowired OperationalAlerts alerts;
    @Autowired OrderRepository orders;
    @Autowired UserRepository users;
    @Autowired SecretTokens tokens;
    @Autowired JdbcTemplate db;
    @MockitoBean EnviaClient envia;

    private static final BigDecimal AMOUNT = new BigDecimal("105.50");
    private static final ShippingLabels.Purchase PURCHASE = new ShippingLabels.Purchase(
            new ShippingLabels.ParcelInput(new BigDecimal("1.00"), 20, 20, 20, "Objetos de hogar"),
            "estafeta", "ground", AMOUNT);
    private static final EnviaClient.Label READY = new EnviaClient.Label("ABC123", "TEST-GUIDE",
            "https://files.envia.com/TEST-GUIDE.pdf", "https://tracking.envia.com/TEST-GUIDE");

    private AuthUser admin;
    private UUID id;

    @BeforeEach void setup() {
        assertTrue(db.queryForObject("select current_database()", String.class).startsWith("olvenio_audit_"));
        String email = "audit-" + UUID.randomUUID() + "@example.test";
        var user = users.save(new User(email, "synthetic", "Eva", "Prueba", null, Role.ADMIN));
        admin = new AuthUser(user.getId(), email, null, "ADMIN", true);
        authenticate();
        Instant now = Instant.now();
        Order order = Order.quote(null, null, "audit@example.test", "Ana", "Prueba", "5512345678", "MXN",
                tokens.hash(tokens.newToken()), now.plusSeconds(300));
        var value = Money.of(new BigDecimal("450.00"), "MXN");
        order.addItem(null, null, "TEST-ITEM", "Objeto", "objeto", "Único", null, value, 1, value);
        order.applyTotals(value, Money.zero("MXN"), value);
        order.shipTo("Ana Prueba", "Calle 1", null, "Cuauhtémoc", "Ciudad de México", "06600", "MX", null, "Juárez");
        order.place("A-" + UUID.randomUUID().toString().substring(0, 20), "audit-" + UUID.randomUUID(), now, null);
        order.recordVerifiedPayment("APPROVED", now);
        id = orders.saveAndFlush(order).getId();

        when(envia.destination(any())).thenReturn(Map.of("postalCode", "06600"));
        when(envia.rates(anyMap(), any(), any())).thenReturn(List.of(
                new EnviaClient.Rate("estafeta", "ground", "Terrestre", AMOUNT, "MXN")));
    }

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void concurrentAndRepeatedPurchasesCreateOneLabel() throws Exception {
        var entered = new CountDownLatch(1);
        var complete = new CountDownLatch(1);
        when(envia.generate(anyMap(), any(), any(), any(), anyString())).thenAnswer(call -> {
            entered.countDown();
            if (!complete.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release Envia");
            return READY;
        });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> {
                authenticate();
                try { return labels.purchase(id, PURCHASE); }
                finally { clearAuthentication(); }
            });
            try {
                assertTrue(entered.await(8, TimeUnit.SECONDS));
                assertEquals("PROCESSING", labels.purchase(id, PURCHASE).status());
            } finally {
                complete.countDown();
            }
            assertEquals("READY", first.get(8, TimeUnit.SECONDS).status());
        }
        assertEquals("TEST-GUIDE", labels.purchase(id, PURCHASE).trackingNumber());
        verify(envia, times(1)).generate(anyMap(), any(), any(), any(), anyString());
    }

    @Test void ambiguousPurchaseNeedsReviewAndCanBeReconciledWithoutAnotherCharge() {
        when(envia.generate(anyMap(), any(), any(), any(), anyString())).thenThrow(new RestClientException("timeout"));
        assertEquals("REVIEW", labels.purchase(id, PURCHASE).status());
        assertEquals("REVIEW", labels.purchase(id, PURCHASE).status());
        var fixed = labels.recover(id, new ShippingLabels.Recovery(READY.shipmentId(), READY.trackingNumber(), READY.url()));
        assertEquals("READY", fixed.status());
        assertEquals(READY.trackingNumber(), fixed.trackingNumber());
        verify(envia, times(1)).generate(anyMap(), any(), any(), any(), anyString());
    }

    @Test void failedOrderEmailCanBeRequeuedOnceAndLeavesTheAlertList() {
        db.update("insert into order_emails(id,order_id,kind,status,last_error) values (gen_random_uuid(),?,'CONFIRMATION','FAILED','MailSendException')", id);
        assertTrue(alerts.active().stream().anyMatch(a -> id.equals(a.orderId()) && a.kind().equals("ORDER_EMAIL")));
        alerts.retryOrderEmail(id, OperationalAlerts.OrderEmail.CONFIRMATION);
        assertEquals("PENDING", db.queryForObject("select status from order_emails where order_id=?", String.class, id));
        assertThrows(ConflictException.class, () -> alerts.retryOrderEmail(id, OperationalAlerts.OrderEmail.CONFIRMATION));
        assertFalse(alerts.active().stream().anyMatch(a -> id.equals(a.orderId()) && a.kind().equals("ORDER_EMAIL")));
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.getAuthorities()));
    }
}
