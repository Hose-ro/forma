package com.velora.admin.api;

import com.velora.admin.application.AdminService;
import com.velora.admin.application.AdminService.Dashboard;
import com.velora.catalog.application.CatalogAdminService;
import com.velora.catalog.application.CatalogDtos.AdminProductView;
import com.velora.catalog.application.CatalogDtos.CategoryCreateRequest;
import com.velora.catalog.application.CatalogDtos.CategoryPatchRequest;
import com.velora.catalog.application.CatalogDtos.CategoryView;
import com.velora.catalog.application.CatalogDtos.DeleteResult;
import com.velora.catalog.application.CatalogDtos.ProductCreateRequest;
import com.velora.catalog.application.CatalogDtos.ProductPatchRequest;
import com.velora.catalog.domain.ProductStatus;
import com.velora.orders.application.OrderDtos.OrderSummaryView;
import com.velora.orders.application.OrderDtos.OrderView;
import com.velora.orders.application.OrderQueryService;
import com.velora.orders.domain.OrderStatus;
import com.velora.shared.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** All routes require ROLE_ADMIN (enforced in SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin")
public class AdminController {

    public record FulfillmentRequest(
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Pattern(regexp = "PREPARING|SHIPPED|DELIVERED") String status,
            @Size(max = 80) String carrier, @Size(max = 120) String trackingNumber) {
    }

    public record CancelRequest(@Size(max = 500) String note) {
    }

    private final AdminService admin;
    private final CatalogAdminService catalog;
    private final OrderQueryService orders;

    public AdminController(AdminService admin, CatalogAdminService catalog, OrderQueryService orders) {
        this.admin = admin;
        this.catalog = catalog;
        this.orders = orders;
    }

    // ---- Products ----
    @GetMapping("/products")
    public PageResponse<AdminProductView> products(@RequestParam(required = false) ProductStatus status,
                                                   @RequestParam(required = false) String category,
                                                   @RequestParam(required = false) String q,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        return catalog.list(status, category, q, page, size);
    }

    @GetMapping("/products/{id}")
    public AdminProductView product(@PathVariable UUID id) {
        return catalog.get(id);
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminProductView createProduct(@Valid @RequestBody ProductCreateRequest req) {
        return admin.createProduct(req);
    }

    @PatchMapping("/products/{id}")
    public AdminProductView updateProduct(@PathVariable UUID id, @Valid @RequestBody ProductPatchRequest req) {
        return admin.updateProduct(id, req);
    }

    @Operation(summary = "Delete a product; archived instead when it has order history")
    @DeleteMapping("/products/{id}")
    public DeleteResult deleteProduct(@PathVariable UUID id) {
        return admin.deleteProduct(id);
    }

    // ---- Categories ----
    @GetMapping("/categories")
    public List<CategoryView> categories() {
        return catalog.categories();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryView createCategory(@Valid @RequestBody CategoryCreateRequest req) {
        return admin.createCategory(req);
    }

    @PatchMapping("/categories/{id}")
    public CategoryView updateCategory(@PathVariable UUID id, @Valid @RequestBody CategoryPatchRequest req) {
        return admin.updateCategory(id, req);
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable UUID id) {
        admin.deleteCategory(id);
    }

    // ---- Orders ----
    @Operation(summary = "List orders (DRAFT quotes excluded unless status=DRAFT)")
    @GetMapping("/orders")
    public PageResponse<OrderSummaryView> orders(@RequestParam(required = false) OrderStatus status,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        return orders.adminList(status, page, size);
    }

    @GetMapping("/orders/{id}")
    public OrderView order(@PathVariable UUID id) {
        return orders.adminGet(id);
    }

    @Operation(summary = "Update logistics of a paid order: PREPARING, SHIPPED (carrier + tracking) or DELIVERED")
    @PostMapping("/orders/{id}/fulfillment")
    public OrderView fulfillOrder(@PathVariable UUID id, @Valid @RequestBody FulfillmentRequest req) {
        return admin.fulfillOrder(id, req.status(), req.carrier(), req.trackingNumber());
    }

    @Operation(summary = "Cancel a quote or pending order (restocks pending orders)")
    @PostMapping("/orders/{id}/cancel")
    public OrderView cancelOrder(@PathVariable UUID id, @Valid @RequestBody(required = false) CancelRequest req) {
        return admin.cancelOrder(id, req == null ? null : req.note());
    }

    // ---- Dashboard ----
    @GetMapping("/dashboard")
    public Dashboard dashboard() {
        return admin.dashboard();
    }
}
