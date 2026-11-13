package com.velora.cart.api;

import static com.velora.shared.config.SecurityConfig.CART_TOKEN_HEADER;

import com.velora.cart.application.CartDtos.AddItemRequest;
import com.velora.cart.application.CartDtos.CartView;
import com.velora.cart.application.CartDtos.UpdateItemRequest;
import com.velora.cart.application.CartService;
import com.velora.shared.security.CurrentUser;
import com.velora.shared.security.GuestSession;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/carts")
@Tag(name = "Cart", description = "Guest carts are bound to the HttpOnly VELORA_GUEST cookie (or X-Cart-Token header)")
public class CartController {

    private final CartService carts;
    private final GuestSession guest;

    public CartController(CartService carts, GuestSession guest) {
        this.carts = carts;
        this.guest = guest;
    }

    @Operation(summary = "Get or create the requester's cart (guest: issues the guest cookie; user: merges the guest cart)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CartView create(HttpServletRequest request, HttpServletResponse response) {
        String token = CurrentUser.id().isPresent() ? guest.token(request).orElse(null)
                : guest.ensure(request, response);
        return carts.create(token);
    }

    @Operation(summary = "Current cart of the session/guest; 204 when there is none")
    @GetMapping("/current")
    public ResponseEntity<CartView> current(HttpServletRequest request) {
        return carts.current(guest.token(request).orElse(null)).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}")
    public CartView get(@PathVariable UUID id, HttpServletRequest request,
                        @RequestHeader(value = CART_TOKEN_HEADER, required = false) String token) {
        return carts.get(id, guest.resolve(token, request));
    }

    @PostMapping("/{id}/items")
    public CartView addItem(@PathVariable UUID id, HttpServletRequest request,
                            @RequestHeader(value = CART_TOKEN_HEADER, required = false) String token,
                            @Valid @RequestBody AddItemRequest req) {
        return carts.addItem(id, guest.resolve(token, request), req);
    }

    @PatchMapping("/{id}/items/{itemId}")
    public CartView updateItem(@PathVariable UUID id, @PathVariable UUID itemId, HttpServletRequest request,
                               @RequestHeader(value = CART_TOKEN_HEADER, required = false) String token,
                               @Valid @RequestBody UpdateItemRequest req) {
        return carts.updateItem(id, guest.resolve(token, request), itemId, req.quantity());
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public CartView removeItem(@PathVariable UUID id, @PathVariable UUID itemId, HttpServletRequest request,
                               @RequestHeader(value = CART_TOKEN_HEADER, required = false) String token) {
        return carts.removeItem(id, guest.resolve(token, request), itemId);
    }
}
