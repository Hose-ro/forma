package com.velora.cart.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class CartDtos {
    private CartDtos() {
    }

    public record AddItemRequest(@NotNull UUID variantId, @NotNull @Min(1) @Max(99) Integer quantity) {
    }

    public record UpdateItemRequest(@NotNull @Min(1) @Max(99) Integer quantity) {
    }

    public record CartLineView(UUID id, UUID variantId, UUID productId, String productSlug, String productName,
                               String variantName, String sku, String imageUrl, BigDecimal unitPrice,
                               int quantity, BigDecimal lineTotal, int stock, boolean available) {
    }

    public record CartView(UUID id, String currency, List<CartLineView> items, int itemCount,
                           BigDecimal subtotal, boolean checkoutReady) {
    }
}
