package com.olvenio.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.olvenio.cart.application.CartDtos.CartLineView;
import com.olvenio.cart.application.CartDtos.CartView;
import com.olvenio.orders.application.OrderDtos.OrderItemView;
import com.olvenio.orders.application.OrderDtos.OrderView;
import com.olvenio.orders.domain.OrderStatus;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "app.frontend-origin=https://store.example.test",
        "app.session-secret=marketing-fixture-session-secret-for-tests",
        "app.marketing.tiktok.enabled=true", "app.marketing.tiktok.pixel-id=StagingPixel123",
        "app.marketing.tiktok.access-token=fixture-token", "app.marketing.tiktok.test-code=TEST_FIXTURE",
        "app.admin.email=", "app.admin.password="
})
@EnabledIfEnvironmentVariable(named = "OLVENIO_MARKETING_DB_TEST", matches = "true")
@Transactional
class MarketingPersistenceTest {
    @Autowired JdbcTemplate db;
    @Autowired TikTokConsent consent;
    @Autowired MarketingTracking marketing;
    @Autowired MarketingDelivery delivery;
    @MockitoBean TikTokEventsClient client;

    @Test
    void purchaseRequiresVerifiedPaidStateIsDeduplicatedAndSharesItsAuthorizedReceipt() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        var request = request("/checkout", true);
        var reply = new MockHttpServletResponse();
        seedOrder(orderId, paymentId, "PENDING_PAYMENT", "PENDING", "MXN", "100.00");

        marketing.orderPlaced(request, reply, order(orderId, OrderStatus.PENDING_PAYMENT, "PENDING"));
        assertThat(reply.getHeader(MarketingTracking.EVENT_HEADER)).isNotBlank();
        var replay = new MockHttpServletResponse();
        marketing.orderPlaced(request, replay, order(orderId, OrderStatus.PENDING_PAYMENT, "PENDING"));
        assertThat(replay.getHeader(MarketingTracking.EVENT_HEADER)).isNull();
        delivery.preparePurchases();
        assertThat(purchaseCount(orderId)).isZero();

        db.update("UPDATE orders SET status='PAID', payment_status='APPROVED' WHERE id=?", orderId);
        db.update("UPDATE payments SET status='APPROVED', approved_at=now(), currency='USD' WHERE id=?", paymentId);
        delivery.preparePurchases();
        assertThat(purchaseCount(orderId)).isZero();
        var mismatchedReceipt = new MockHttpServletResponse();
        var mismatchedPage = request("/checkout/success", false);
        mismatchedPage.setCookies(request.getCookies());
        marketing.authorizedPurchase(mismatchedPage, mismatchedReceipt, order(orderId, OrderStatus.PAID, "APPROVED"));
        assertThat(mismatchedReceipt.getHeader(MarketingTracking.EVENT_HEADER)).isNull();
        db.update("UPDATE payments SET currency='MXN' WHERE id=?", paymentId);

        delivery.preparePurchases();
        delivery.preparePurchases();
        assertThat(purchaseCount(orderId)).isOne();
        String storedId = db.queryForObject("SELECT event_id FROM marketing_events WHERE order_id=? AND event_name='Purchase'",
                String.class, orderId);
        var purchasePage = request("/checkout/success?orderId=" + orderId + "&secret=private", false);
        purchasePage.setCookies(request.getCookies());
        var purchaseReply = new MockHttpServletResponse();
        marketing.authorizedPurchase(purchasePage, purchaseReply, order(orderId, OrderStatus.PAID, "APPROVED"));
        assertThat(purchaseReply.getHeader(MarketingTracking.EVENT_HEADER)).isEqualTo(storedId);
        assertThat(db.queryForObject("SELECT page_url FROM marketing_events WHERE event_id=?", String.class, storedId))
                .isEqualTo("https://store.example.test/checkout");

        var differentBrowser = request("/checkout/success", true);
        var privateReply = new MockHttpServletResponse();
        marketing.authorizedPurchase(differentBrowser, privateReply, order(orderId, OrderStatus.PAID, "APPROVED"));
        assertThat(privateReply.getHeader(MarketingTracking.EVENT_HEADER)).isNull();

        consent.update(request, new MockHttpServletResponse(), false, null);
        assertThat(db.queryForObject("SELECT status FROM marketing_events WHERE event_id=?", String.class, storedId))
                .isEqualTo("SKIPPED");
        assertThat(db.queryForObject("SELECT ttclid FROM marketing_events WHERE event_id=?", String.class, storedId)).isNull();
        marketing.authorizedPurchase(purchasePage, privateReply, order(orderId, OrderStatus.PAID, "APPROVED"));
        assertThat(privateReply.getHeader(MarketingTracking.EVENT_HEADER)).isNull();
    }

    @Test
    void requiresConsentSkipsAdminAndRetriesTimeoutWithTheSameId() throws Exception {
        UUID variant = UUID.randomUUID();
        var line = new CartLineView(UUID.randomUUID(), variant, UUID.randomUUID(), "product", "example", "example",
                "SKU", null, new BigDecimal("12.50"), 3, new BigDecimal("37.50"), 8, true);
        var cart = new CartView(UUID.randomUUID(), "MXN", List.of(line), 3, line.lineTotal(), true);
        var response = new MockHttpServletResponse();
        marketing.cartAdded(request("/shop", false), response, cart, variant, 2);
        marketing.cartAdded(request("/admin", true), response, cart, variant, 2);
        assertThat(response.getHeader(MarketingTracking.EVENT_HEADER)).isNull();

        marketing.cartAdded(request("/shop?q=private", true), response, cart, variant, 2);
        String eventId = response.getHeader(MarketingTracking.EVENT_HEADER);
        assertThat(eventId).isNotBlank();

        when(client.send(argThat(event -> event.id().equals(eventId)))).thenThrow(new TimeoutException());
        delivery.poll();
        assertThat(db.queryForObject("SELECT status FROM marketing_events WHERE event_id=?", String.class, eventId))
                .isEqualTo("PENDING");
        db.update("UPDATE marketing_events SET due_at=now() WHERE event_id=?", eventId);
        reset(client);
        when(client.send(argThat(event -> event.id().equals(eventId)))).thenReturn(new TikTokEventsClient.Result(true, false, 200, 0));
        delivery.poll();
        verify(client).send(argThat(event -> event.id().equals(eventId) && event.name().equals("AddToCart")
                && event.url().equals("https://store.example.test/shop")
                && event.properties().path("value").decimalValue().compareTo(new BigDecimal("25.00")) == 0
                && event.properties().path("contents").get(0).path("quantity").asInt() == 2));
        assertThat(db.queryForObject("SELECT status FROM marketing_events WHERE event_id=?", String.class, eventId))
                .isEqualTo("SENT");
        assertThat(db.queryForObject("SELECT attempts FROM marketing_events WHERE event_id=?", Integer.class, eventId)).isEqualTo(2);
    }

    private MockHttpServletRequest request(String path, boolean allow) {
        var request = new MockHttpServletRequest();
        request.addHeader("Referer", "https://store.example.test" + path);
        if (allow) {
            var response = new MockHttpServletResponse();
            consent.update(request, response, true, "ClickFixture123");
            String ticket = response.getHeaders("Set-Cookie").getFirst().split("[=;]")[1];
            request.setCookies(new Cookie(TikTokConsent.TICKET, ticket));
        }
        return request;
    }

    private void seedOrder(UUID id, UUID paymentId, String status, String paymentStatus, String currency, String amount) {
        db.update("""
                INSERT INTO orders (id, order_number, status, email, customer_first_name, customer_last_name, currency,
                  subtotal, shipping_total, total, access_token_hash, quote_expires_at, placed_at, payment_status)
                VALUES (?, ?, ?, 'fixture@example.test', 'Fixture', 'Fixture', ?, 90, 10, 100,
                        repeat('a', 64), now(), now(), ?)
                """, id, "T-" + id.toString().substring(0, 12), status, currency, paymentStatus);
        db.update("INSERT INTO payments (id,order_id,status,amount,currency,provider) VALUES (?,?,?,?,?,'PAYPAL')",
                paymentId, id, paymentStatus, new BigDecimal(amount), currency);
    }

    private int purchaseCount(UUID orderId) {
        return db.queryForObject("SELECT count(*) FROM marketing_events WHERE order_id=? AND event_name='Purchase'",
                Integer.class, orderId);
    }

    private static OrderView order(UUID id, OrderStatus status, String paymentStatus) {
        var now = Instant.now();
        var variant = new OrderItemView(null, UUID.randomUUID(), "SKU", "Product", "product", "Variant", null,
                new BigDecimal("90.00"), 1, new BigDecimal("90.00"));
        return new OrderView(id, "TEST", status, paymentStatus, null, null, "MXN", List.of(variant),
                new BigDecimal("90.00"), new BigDecimal("10.00"), new BigDecimal("100.00"),
                now, now, null, now, List.of(), null, null, null, null);
    }
}
