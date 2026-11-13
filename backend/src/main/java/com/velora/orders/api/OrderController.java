package com.velora.orders.api;

import static com.velora.shared.config.SecurityConfig.ORDER_TOKEN_HEADER;

import com.velora.orders.application.OrderDtos.OrderSummaryView;
import com.velora.orders.application.OrderDtos.OrderView;
import com.velora.orders.application.OrderQueryService;
import com.velora.shared.api.PageResponse;
import com.velora.shared.security.AuthUser;
import com.velora.shared.security.GuestSession;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Orders")
public class OrderController {

    private final OrderQueryService orders;
    private final GuestSession guest;

    public OrderController(OrderQueryService orders, GuestSession guest) {
        this.orders = orders;
        this.guest = guest;
    }

    @Operation(summary = "Orders placed as a guest from this browser (guest cookie)")
    @GetMapping("/api/v1/orders")
    public PageResponse<OrderSummaryView> guestOrders(HttpServletRequest request,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size) {
        return orders.guestOrders(guest.token(request).orElse(null), page, size);
    }

    @Operation(summary = "Get an order/quote (owner session, admin, guest cookie or X-Order-Token)")
    @GetMapping("/api/v1/orders/{id}")
    public OrderView get(@PathVariable UUID id, HttpServletRequest request,
                         @RequestHeader(value = ORDER_TOKEN_HEADER, required = false) String token) {
        return orders.get(id, guest.resolve(token, request));
    }

    @Operation(summary = "Orders of the logged-in customer")
    @GetMapping("/api/v1/me/orders")
    public PageResponse<OrderSummaryView> mine(@AuthenticationPrincipal AuthUser user,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        return orders.mine(user.id(), page, size);
    }
}
