package com.velora.shared;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.velora.cart.application.CartService;
import com.velora.cart.domain.Cart;
import com.velora.cart.infrastructure.CartRepository;
import com.velora.catalog.infrastructure.ProductVariantRepository;
import com.velora.orders.application.OrderQueryService;
import com.velora.orders.domain.Order;
import com.velora.orders.infrastructure.OrderRepository;
import com.velora.shared.config.AppProperties;
import com.velora.shared.domain.NotFoundException;
import com.velora.shared.security.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ResourceBoundaryTest {
    private final AppProperties props = new AppProperties(null, "test-secret-test-secret-test-secret-42", false, "MXN", null, null);
    private final SecretTokens tokens = new SecretTokens(props);
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void anotherCustomerCannotReadOrder() {
        UUID owner = UUID.randomUUID(), id = UUID.randomUUID();
        var order = Order.quote(owner, null, "test@example.com", "Test", "User", null, "MXN",
                tokens.hash(tokens.newToken()), Instant.now().plusSeconds(600));
        var orders = mock(OrderRepository.class);
        when(orders.findById(id)).thenReturn(Optional.of(order));
        var user = new AuthUser(UUID.randomUUID(), "other@example.com", null, "CUSTOMER", true);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        assertThatThrownBy(() -> new OrderQueryService(orders, tokens).get(id, null)).isInstanceOf(NotFoundException.class);
    }

    @Test void guestCannotReadAnotherCart() {
        UUID id = UUID.randomUUID();
        var carts = mock(CartRepository.class);
        when(carts.findById(id)).thenReturn(Optional.of(Cart.forGuest(tokens.hash(tokens.newToken()), "MXN")));
        var variants = mock(ProductVariantRepository.class);
        assertThatThrownBy(() -> new CartService(carts, variants, tokens, props).get(id, tokens.newToken()))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(variants);
    }
}
