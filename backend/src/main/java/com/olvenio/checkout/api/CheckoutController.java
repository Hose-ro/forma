package com.olvenio.checkout.api;

import static com.olvenio.shared.config.SecurityConfig.CART_TOKEN_HEADER;
import static com.olvenio.shared.config.SecurityConfig.IDEMPOTENCY_HEADER;
import static com.olvenio.shared.config.SecurityConfig.ORDER_TOKEN_HEADER;

import com.olvenio.checkout.application.CheckoutDtos.PlaceOrderRequest;
import com.olvenio.checkout.application.CheckoutDtos.QuoteRequest;
import com.olvenio.checkout.application.CheckoutService;
import com.olvenio.orders.application.OrderDtos.OrderView;
import com.olvenio.shared.security.CurrentUser;
import com.olvenio.shared.security.GuestSession;
import com.olvenio.marketing.MarketingTracking;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Checkout", description = "Quotes and order placement; payment is confirmed only by the verified provider integration")
public class CheckoutController {

    private final CheckoutService checkout;
    private final GuestSession guest;
    private final MarketingTracking marketing;

    public CheckoutController(CheckoutService checkout, GuestSession guest, MarketingTracking marketing) {
        this.checkout = checkout;
        this.guest = guest;
        this.marketing = marketing;
    }

    @Operation(summary = "Create a priced, expiring quote from a cart. Guest quotes are bound to the guest cookie.")
    @PostMapping("/api/v1/checkout/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView quote(@Valid @RequestBody QuoteRequest req, HttpServletRequest request,
                           HttpServletResponse response,
                           @RequestHeader(value = CART_TOKEN_HEADER, required = false) String cartToken) {
        String guestToken = CurrentUser.id().isPresent() ? guest.token(request).orElse(null)
                : cartToken != null && !cartToken.isBlank() ? cartToken : guest.ensure(request, response);
        OrderView quote = checkout.createQuote(req, guest.resolve(cartToken, request), guestToken);
        marketing.checkoutStarted(request, response, quote);
        return quote;
    }

    @Operation(summary = "Confirm a quote as an order (PENDING_PAYMENT). Requires Idempotency-Key; 201 new, 200 replay.")
    @PostMapping("/api/v1/orders")
    public ResponseEntity<OrderView> place(@Valid @RequestBody PlaceOrderRequest req, HttpServletRequest request,
                                           HttpServletResponse response,
                                           @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                           @RequestHeader(value = ORDER_TOKEN_HEADER, required = false) String token) {
        var result = checkout.placeOrder(req.quoteId(), key, guest.resolve(token, request));
        if (result.created()) marketing.orderPlaced(request, response, result.order());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.order());
    }
}
