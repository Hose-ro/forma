package com.olvenio.checkout.application;

import com.olvenio.cart.application.CartService;
import com.olvenio.cart.domain.Cart;
import com.olvenio.cart.domain.CartItem;
import com.olvenio.cart.infrastructure.CartRepository;
import com.olvenio.catalog.domain.ProductVariant;
import com.olvenio.catalog.infrastructure.ProductVariantRepository;
import com.olvenio.checkout.application.CheckoutDtos.QuoteRequest;
import com.olvenio.checkout.domain.OrderPricing;
import com.olvenio.orders.application.OrderDtos.OrderView;
import com.olvenio.orders.application.OrderMapper;
import com.olvenio.orders.domain.Order;
import com.olvenio.orders.domain.OrderItem;
import com.olvenio.orders.infrastructure.OrderRepository;
import com.olvenio.shared.address.PostalDirectory;
import com.olvenio.shared.config.AppProperties;
import com.olvenio.shared.domain.BusinessRuleException;
import com.olvenio.shared.domain.ConflictException;
import com.olvenio.shared.domain.InvalidRequestException;
import com.olvenio.shared.domain.Money;
import com.olvenio.shared.domain.NotFoundException;
import com.olvenio.shared.security.CurrentUser;
import com.olvenio.shared.security.ResourceAccess;
import com.olvenio.shared.security.SecretTokens;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quote = DRAFT order with server-computed price snapshots and expiry. Placing an order confirms the quote
 * once, re-validating prices and reserving stock atomically for a limited time (see ReservationService).
 */
@Service
public class CheckoutService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_.:-]{8,100}$");

    public record PlacementResult(OrderView order, boolean created) {
    }

    private final CartService cartService;
    private final CartRepository carts;
    private final ProductVariantRepository variants;
    private final OrderRepository orders;
    private final SecretTokens tokens;
    private final AppProperties props;
    private final Clock clock;
    private final PostalDirectory postalDirectory;
    /** How long an unpaid order keeps its stock. The payment preference expires at the same instant. */
    @org.springframework.beans.factory.annotation.Value("${app.checkout.reservation-ttl:24h}")
    private java.time.Duration reservationTtl = java.time.Duration.ofHours(24);

    public CheckoutService(CartService cartService, CartRepository carts, ProductVariantRepository variants,
                           OrderRepository orders, SecretTokens tokens, AppProperties props,
                           Optional<Clock> clock, PostalDirectory postalDirectory) {
        this.cartService = cartService;
        this.carts = carts;
        this.variants = variants;
        this.orders = orders;
        this.tokens = tokens;
        this.props = props;
        this.clock = clock.orElse(Clock.systemUTC());
        this.postalDirectory = postalDirectory;
    }

    @Transactional
    public OrderView createQuote(QuoteRequest req, String cartToken, String guestToken) {
        var a = req.shippingAddress();
        if (!"MX".equals(a.country())) throw new InvalidRequestException("Por ahora solo enviamos a México.");
        var postal = postalDirectory.validate(a.postalCode(), a.state(), a.city(), blankToNull(a.neighborhood()));
        Cart cart = cartService.authorizedForUpdate(req.cartId(), cartToken);
        if (cart.getItems().isEmpty()) {
            throw new BusinessRuleException("Cart is empty");
        }
        Map<UUID, ProductVariant> byId = variants.findAllWithProduct(
                        cart.getItems().stream().map(CartItem::getVariantId).toList()).stream()
                .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));

        List<OrderPricing.Line> lines = cart.getItems().stream().map(item -> {
            ProductVariant v = byId.get(item.getVariantId());
            if (v == null || !v.isPurchasable(item.getQuantity())) {
                throw new BusinessRuleException("An item in the cart is no longer available in that quantity");
            }
            if (!v.getCurrency().equals(cart.getCurrency())) {
                throw new BusinessRuleException("Currency mismatch in cart");
            }
            return new OrderPricing.Line(v.unitPrice(), item.getQuantity());
        }).toList();
        var goodsTotal = OrderPricing.compute(lines, Money.zero(cart.getCurrency())).subtotal().rounded();
        var shippingRate = props.checkout().shippingFor(postal.state(), goodsTotal)
                .orElseThrow(() -> new InvalidRequestException("Por ahora no hacemos envíos a ese estado."));
        OrderPricing.Breakdown price = OrderPricing.compute(lines, Money.of(shippingRate, cart.getCurrency()));

        // Guests: the order is bound to the browser's guest token. Users: access goes through the session,
        // so the token is a random secret nobody holds.
        String accessToken = CurrentUser.id().isEmpty() && guestToken != null ? guestToken : tokens.newToken();
        Instant now = clock.instant();
        var c = req.customer();
        Order order = Order.quote(CurrentUser.id().orElse(null), cart.getId(), c.email(), c.firstName(),
                c.lastName(), blankToNull(c.phone()), cart.getCurrency(), tokens.hash(accessToken),
                now.plus(props.checkout().quoteTtl()));
        order.bindCartRevision(cart.getCheckoutRevision());
        for (int i = 0; i < cart.getItems().size(); i++) {
            CartItem item = cart.getItems().get(i);
            ProductVariant v = byId.get(item.getVariantId());
            var p = v.getProduct();
            order.addItem(p.getId(), v.getId(), v.getSku(), p.getName(), p.getSlug(), v.getName(),
                    v.getImageUrl() != null ? v.getImageUrl() : p.primaryImageUrl().orElse(null),
                    v.unitPrice(), item.getQuantity(), price.lineTotals().get(i));
        }
        order.shipTo(a.recipientName(), a.line1(), blankToNull(a.line2()), postal.municipality(), postal.state(),
                postal.postalCode(), a.country(), blankToNull(a.phone()), blankToNull(a.neighborhood()));
        order.applyTotals(price.subtotal(), price.shipping(), price.total());
        return OrderMapper.view(orders.saveAndFlush(order), false);
    }

    @Transactional
    public PlacementResult placeOrder(UUID quoteId, String idempotencyKey, String orderToken) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new InvalidRequestException("Idempotency-Key header is required (8-100 chars: A-Z a-z 0-9 _ . : -)");
        }
        Optional<Order> previous = orders.findByIdempotencyKey(idempotencyKey);
        if (previous.isPresent()) {
            requireAccess(previous.get(), orderToken);
            if (!previous.get().getId().equals(quoteId)) {
                throw new ConflictException("Idempotency-Key was already used for a different order");
            }
            return new PlacementResult(OrderMapper.view(previous.get(), false), false);
        }

        // Row lock serializes concurrent confirmations of the same quote.
        Order order = orders.lockById(quoteId).orElseThrow(() -> new NotFoundException("Quote not found"));
        requireAccess(order, orderToken);
        Instant now = clock.instant();
        if (order.checkPlacement(idempotencyKey, now) == Order.PlacementCheck.REPLAY) {
            return new PlacementResult(OrderMapper.view(order, false), false);
        }

        if (order.getCartId() == null) throw new ConflictException("Cart no longer exists; request a new quote");
        Cart cart = carts.lockById(order.getCartId())
                .orElseThrow(() -> new ConflictException("Cart no longer exists; request a new quote"));
        order.requireCartRevision(cart.getCheckoutRevision());
        reserveStockAtQuotedPrices(order);
        order.place(String.format("OLV-%06d", orders.nextOrderNumber()), idempotencyKey, now,
                CurrentUser.id().orElse(null));
        order.reserveUntil(now.plus(reservationTtl));
        cart.clear();
        return new PlacementResult(OrderMapper.view(orders.saveAndFlush(order), false), true);
    }

    private void reserveStockAtQuotedPrices(Order order) {
        List<UUID> ids = order.getItems().stream().map(OrderItem::getVariantId).toList();
        if (ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ConflictException("A product in this quote no longer exists; request a new quote");
        }
        Map<UUID, ProductVariant> locked = variants.lockAllById(ids).stream()
                .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));
        for (OrderItem item : order.getItems()) {
            ProductVariant v = locked.get(item.getVariantId());
            if (v == null || !v.isPurchasable(item.getQuantity())) {
                throw new ConflictException("Stock changed for " + item.getSku() + "; request a new quote");
            }
            if (!v.unitPrice().sameAmountAs(Money.of(item.getUnitPrice(), item.getCurrency()))) {
                throw new ConflictException("Prices changed since the quote; request a new quote");
            }
            v.decreaseStock(item.getQuantity());
        }
    }

    private void requireAccess(Order order, String orderToken) {
        if (!ResourceAccess.allowed(order.getUserId(), order.getAccessTokenHash(), CurrentUser.id(), orderToken,
                tokens)) {
            throw new NotFoundException("Quote not found");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
