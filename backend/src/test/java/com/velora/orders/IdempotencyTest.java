package com.velora.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velora.cart.application.CartService;
import com.velora.cart.infrastructure.CartRepository;
import com.velora.catalog.infrastructure.ProductVariantRepository;
import com.velora.checkout.application.CheckoutService;
import com.velora.orders.domain.Order;
import com.velora.orders.infrastructure.OrderRepository;
import com.velora.shared.config.AppProperties;
import com.velora.shared.domain.ConflictException;
import com.velora.shared.domain.InvalidRequestException;
import com.velora.shared.domain.Money;
import com.velora.shared.domain.NotFoundException;
import com.velora.shared.security.SecretTokens;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class IdempotencyTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private final AppProperties props = new AppProperties(null, "test-secret-test-secret-test-secret-42", false,
            "MXN", null, null);
    private final SecretTokens tokens = new SecretTokens(props);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final ProductVariantRepository variants = mock(ProductVariantRepository.class);
    private final CartRepository carts = mock(CartRepository.class);
    private final CheckoutService checkout = new CheckoutService(mock(CartService.class), carts,
            variants, orders, tokens, props, Optional.of(Clock.fixed(NOW, ZoneOffset.UTC)));

    private Order quote(String token, Instant expiresAt) {
        Order o = Order.quote(null, null, "guest@example.com", "Ana", "Pérez", null, "MXN", tokens.hash(token),
                expiresAt);
        Money price = Money.of(new BigDecimal("100.00"), "MXN");
        o.addItem(UUID.randomUUID(), UUID.randomUUID(), "SKU-1", "P", "p", "V", null, price, 1, price);
        ReflectionTestUtils.setField(o, "id", UUID.randomUUID());
        return o;
    }

    @Test
    void sameKeySameQuoteReplaysWithoutTouchingStock() {
        String token = tokens.newToken();
        Order placed = quote(token, NOW.plusSeconds(600));
        placed.place("VEL-001000", "key-12345678", NOW, null);
        when(orders.findByIdempotencyKey("key-12345678")).thenReturn(Optional.of(placed));

        var result = checkout.placeOrder(placed.getId(), "key-12345678", token);

        assertThat(result.created()).isFalse();
        assertThat(result.order().orderNumber()).isEqualTo("VEL-001000");
        verify(orders, never()).lockById(any());
        verify(variants, never()).lockAllById(any());
    }

    @Test
    void reusedKeyForAnotherQuoteIsRejected() {
        String token = tokens.newToken();
        Order placed = quote(token, NOW.plusSeconds(600));
        when(orders.findByIdempotencyKey("key-12345678")).thenReturn(Optional.of(placed));
        assertThatThrownBy(() -> checkout.placeOrder(UUID.randomUUID(), "key-12345678", token))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void replayStillRequiresAccessToken() {
        Order placed = quote(tokens.newToken(), NOW.plusSeconds(600));
        placed.place("VEL-001000", "key-12345678", NOW, null);
        when(orders.findByIdempotencyKey("key-12345678")).thenReturn(Optional.of(placed));
        assertThatThrownBy(() -> checkout.placeOrder(placed.getId(), "key-12345678", "wrong"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void missingOrMalformedKeyIsRejected() {
        assertThatThrownBy(() -> checkout.placeOrder(UUID.randomUUID(), null, null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> checkout.placeOrder(UUID.randomUUID(), "short", null))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void confirmedQuoteCannotBePlacedAgainWithDifferentKeyAndExpiredQuoteIsRejected() {
        Order placed = quote(tokens.newToken(), NOW.plusSeconds(600));
        placed.place("VEL-001000", "key-12345678", NOW, null);
        assertThat(placed.checkPlacement("key-12345678", NOW)).isEqualTo(Order.PlacementCheck.REPLAY);
        assertThatThrownBy(() -> placed.checkPlacement("other-key-123", NOW)).isInstanceOf(ConflictException.class);

        Order expired = quote(tokens.newToken(), NOW.minusSeconds(1));
        assertThatThrownBy(() -> expired.checkPlacement("key-12345678", NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void secondQuoteForAlreadyConsumedCartCannotReserveStock() {
        String token = tokens.newToken();
        var cart = com.velora.cart.domain.Cart.forGuest(tokens.hash(token), "MXN");
        UUID cartId = UUID.randomUUID();
        ReflectionTestUtils.setField(cart, "id", cartId);
        Order staleQuote = quote(token, NOW.plusSeconds(600));
        ReflectionTestUtils.setField(staleQuote, "cartId", cartId);
        staleQuote.bindCartRevision(cart.getCheckoutRevision());
        cart.clear(); // First checkout consumes this revision under the cart row lock.
        when(orders.lockById(staleQuote.getId())).thenReturn(Optional.of(staleQuote));
        when(carts.lockById(cartId)).thenReturn(Optional.of(cart));
        assertThatThrownBy(() -> checkout.placeOrder(staleQuote.getId(), "second-checkout-key", token))
                .isInstanceOf(ConflictException.class);
        verify(variants, never()).lockAllById(any());
    }
}
