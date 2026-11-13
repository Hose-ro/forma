package com.velora.cart.application;

import com.velora.cart.application.CartDtos.AddItemRequest;
import com.velora.cart.application.CartDtos.CartLineView;
import com.velora.cart.application.CartDtos.CartView;
import com.velora.cart.domain.Cart;
import com.velora.cart.domain.CartItem;
import com.velora.cart.infrastructure.CartRepository;
import com.velora.catalog.domain.ProductVariant;
import com.velora.catalog.infrastructure.ProductVariantRepository;
import com.velora.shared.config.AppProperties;
import com.velora.shared.domain.BusinessRuleException;
import com.velora.shared.domain.Money;
import com.velora.shared.domain.NotFoundException;
import com.velora.shared.security.CurrentUser;
import com.velora.shared.security.ResourceAccess;
import com.velora.shared.security.SecretTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CartService {

    private final CartRepository carts;
    private final ProductVariantRepository variants;
    private final SecretTokens tokens;
    private final String currency;

    public CartService(CartRepository carts, ProductVariantRepository variants, SecretTokens tokens,
                       AppProperties props) {
        this.carts = carts;
        this.variants = variants;
        this.tokens = tokens;
        this.currency = props.currency();
    }

    /**
     * Logged-in users get (or reuse) their personal cart, absorbing any guest cart of this browser.
     * Guests get (or reuse) the cart bound to their guest token.
     */
    public CartView create(String guestToken) {
        var userId = CurrentUser.id();
        if (userId.isPresent()) {
            Cart cart = carts.findByUserId(userId.get())
                    .orElseGet(() -> carts.saveAndFlush(Cart.forUser(userId.get(), currency)));
            if (guestToken != null) mergeGuestCart(cart, guestToken);
            return view(carts.saveAndFlush(cart));
        }
        if (guestToken == null) throw new NotFoundException("Cart not found");
        String hash = tokens.hash(guestToken);
        Cart cart = carts.findFirstByTokenHashAndUserIdIsNullOrderByCreatedAtDesc(hash)
                .orElseGet(() -> carts.saveAndFlush(Cart.forGuest(hash, currency)));
        return view(cart);
    }

    /** Current cart of the requester (user session or guest token), if any. Never creates one. */
    @Transactional(readOnly = true)
    public Optional<CartView> current(String guestToken) {
        var userId = CurrentUser.id();
        Optional<Cart> cart = userId.isPresent() ? carts.findByUserId(userId.get())
                : guestToken == null ? Optional.empty()
                : carts.findFirstByTokenHashAndUserIdIsNullOrderByCreatedAtDesc(tokens.hash(guestToken));
        return cart.map(this::view);
    }

    private void mergeGuestCart(Cart userCart, String guestToken) {
        carts.findFirstByTokenHashAndUserIdIsNullOrderByCreatedAtDesc(tokens.hash(guestToken)).ifPresent(guest -> {
            Map<UUID, ProductVariant> byId = variants.findAllWithProduct(
                            guest.getItems().stream().map(CartItem::getVariantId).toList()).stream()
                    .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));
            for (CartItem item : guest.getItems()) {
                ProductVariant v = byId.get(item.getVariantId());
                if (v == null || !v.isPurchasable(1)) continue;
                int already = userCart.getItems().stream().filter(i -> i.getVariantId().equals(v.getId()))
                        .mapToInt(CartItem::getQuantity).sum();
                int room = Math.min(Cart.MAX_QUANTITY, v.getStock()) - already;
                if (room > 0 && (already > 0 || userCart.getItems().size() < Cart.MAX_LINES)) {
                    userCart.add(v.getId(), Math.min(item.getQuantity(), room));
                }
            }
            carts.delete(guest);
        });
    }

    @Transactional(readOnly = true)
    public CartView get(UUID cartId, String cartToken) {
        return view(authorized(cartId, cartToken));
    }

    public CartView addItem(UUID cartId, String cartToken, AddItemRequest req) {
        Cart cart = authorizedForUpdate(cartId, cartToken);
        ProductVariant variant = variants.findAllWithProduct(List.of(req.variantId())).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Variant not found"));
        int current = cart.getItems().stream().filter(i -> i.getVariantId().equals(variant.getId()))
                .mapToInt(CartItem::getQuantity).sum();
        ensurePurchasable(variant, current + req.quantity());
        cart.add(variant.getId(), req.quantity());
        return view(carts.saveAndFlush(cart));
    }

    public CartView updateItem(UUID cartId, String cartToken, UUID itemId, int quantity) {
        Cart cart = authorizedForUpdate(cartId, cartToken);
        CartItem item = cart.item(itemId);
        ProductVariant variant = variants.findAllWithProduct(List.of(item.getVariantId())).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Variant not found"));
        ensurePurchasable(variant, quantity);
        cart.changeQuantity(itemId, quantity);
        return view(carts.saveAndFlush(cart));
    }

    public CartView removeItem(UUID cartId, String cartToken, UUID itemId) {
        Cart cart = authorizedForUpdate(cartId, cartToken);
        cart.remove(itemId);
        return view(carts.saveAndFlush(cart));
    }

    /** Loads a cart enforcing ownership: owner session or valid guest token. Unknown and foreign carts are both 404. */
    public Cart authorized(UUID cartId, String cartToken) {
        Cart cart = carts.findById(cartId).orElseThrow(() -> new NotFoundException("Cart not found"));
        return requireAccess(cart, cartToken);
    }

    public Cart authorizedForUpdate(UUID cartId, String cartToken) {
        Cart cart = carts.lockById(cartId).orElseThrow(() -> new NotFoundException("Cart not found"));
        return requireAccess(cart, cartToken);
    }

    private Cart requireAccess(Cart cart, String cartToken) {
        if (!ResourceAccess.allowed(cart.getUserId(), cart.getTokenHash(), CurrentUser.id(), cartToken, tokens)) {
            throw new NotFoundException("Cart not found");
        }
        return cart;
    }

    private static void ensurePurchasable(ProductVariant variant, int quantity) {
        Cart.validateQuantity(quantity);
        if (!variant.isPurchasable(1)) {
            throw new BusinessRuleException("Product variant is not available");
        }
        if (variant.getStock() < quantity) {
            throw new BusinessRuleException("Only " + variant.getStock() + " units available");
        }
    }

    CartView view(Cart cart) {
        Map<UUID, ProductVariant> byId = variants.findAllWithProduct(
                        cart.getItems().stream().map(CartItem::getVariantId).toList()).stream()
                .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));
        Money subtotal = Money.zero(cart.getCurrency());
        List<CartLineView> lines = new ArrayList<>();
        boolean ready = !cart.getItems().isEmpty();
        int count = 0;
        for (CartItem item : cart.getItems()) {
            ProductVariant v = byId.get(item.getVariantId());
            if (v == null) continue;
            Money line = v.unitPrice().times(item.getQuantity());
            boolean available = v.isPurchasable(item.getQuantity());
            ready &= available;
            subtotal = subtotal.plus(line);
            count += item.getQuantity();
            var p = v.getProduct();
            lines.add(new CartLineView(item.getId(), v.getId(), p.getId(), p.getSlug(), p.getName(), v.getName(),
                    v.getSku(), v.getImageUrl() != null ? v.getImageUrl() : p.primaryImageUrl().orElse(null),
                    Money.display(v.getPrice()), item.getQuantity(), line.rounded(), v.getStock(), available));
        }
        return new CartView(cart.getId(), cart.getCurrency(), lines, count, subtotal.rounded(), ready);
    }
}
