package com.olvenio.shared;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.olvenio.cart.application.CartService;
import com.olvenio.cart.domain.Cart;
import com.olvenio.cart.infrastructure.CartRepository;
import com.olvenio.catalog.infrastructure.ProductVariantRepository;
import com.olvenio.orders.application.OrderQueryService;
import com.olvenio.orders.domain.Order;
import com.olvenio.orders.infrastructure.OrderRepository;
import com.olvenio.shared.config.AppProperties;
import com.olvenio.shared.domain.NotFoundException;
import com.olvenio.shared.security.*;
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
