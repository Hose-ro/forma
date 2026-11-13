package com.velora.admin.application;

import com.velora.admin.domain.AuditLog;
import com.velora.admin.infrastructure.AuditLogRepository;
import com.velora.catalog.application.CatalogAdminService;
import com.velora.catalog.application.CatalogDtos.AdminProductView;
import com.velora.catalog.application.CatalogDtos.CategoryCreateRequest;
import com.velora.catalog.application.CatalogDtos.CategoryPatchRequest;
import com.velora.catalog.application.CatalogDtos.CategoryView;
import com.velora.catalog.application.CatalogDtos.DeleteResult;
import com.velora.catalog.application.CatalogDtos.ProductCreateRequest;
import com.velora.catalog.application.CatalogDtos.ProductPatchRequest;
import com.velora.catalog.domain.ProductStatus;
import com.velora.orders.application.OrderAdminService;
import com.velora.orders.application.OrderDtos.OrderView;
import com.velora.orders.application.OrderQueryService;
import com.velora.orders.domain.OrderStatus;
import com.velora.shared.security.CurrentUser;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin use cases: delegates to module services and records an audit trail in the same transaction. */
@Service
public class AdminService {

    public record Dashboard(long totalProducts, long activeProducts, long registeredOrders, long pendingOrders) {
    }

    private final CatalogAdminService catalog;
    private final OrderQueryService orderQueries;
    private final OrderAdminService orderAdmin;
    private final AuditLogRepository audit;

    public AdminService(CatalogAdminService catalog, OrderQueryService orderQueries, OrderAdminService orderAdmin,
                        AuditLogRepository audit) {
        this.catalog = catalog;
        this.orderQueries = orderQueries;
        this.orderAdmin = orderAdmin;
        this.audit = audit;
    }

    @Transactional
    public AdminProductView createProduct(ProductCreateRequest req) {
        AdminProductView view = catalog.create(req);
        record("PRODUCT_CREATED", "product", view.id(), Map.of("slug", view.slug(), "status", view.status()));
        return view;
    }

    @Transactional
    public AdminProductView updateProduct(UUID id, ProductPatchRequest req) {
        AdminProductView view = catalog.update(id, req);
        record("PRODUCT_UPDATED", "product", id, Map.of("slug", view.slug(), "status", view.status(),
                "price", view.price()));
        return view;
    }

    @Transactional
    public DeleteResult deleteProduct(UUID id) {
        DeleteResult result = catalog.delete(id);
        record("PRODUCT_" + result.outcome(), "product", id, Map.of());
        return result;
    }

    @Transactional
    public CategoryView createCategory(CategoryCreateRequest req) {
        CategoryView view = catalog.createCategory(req);
        record("CATEGORY_CREATED", "category", view.id(), Map.of("slug", view.slug()));
        return view;
    }

    @Transactional
    public CategoryView updateCategory(UUID id, CategoryPatchRequest req) {
        CategoryView view = catalog.updateCategory(id, req);
        record("CATEGORY_UPDATED", "category", id, Map.of("slug", view.slug()));
        return view;
    }

    @Transactional
    public void deleteCategory(UUID id) {
        catalog.deleteCategory(id);
        record("CATEGORY_DELETED", "category", id, Map.of());
    }

    @Transactional
    public OrderView cancelOrder(UUID id, String note) {
        OrderView view = orderAdmin.cancel(id, note);
        Map<String, Object> details = new HashMap<>();
        if (note != null) details.put("note", note);
        record("ORDER_CANCELLED", "order", id, details);
        return view;
    }

    @Transactional
    public OrderView fulfillOrder(UUID id, String status, String carrier, String trackingNumber) {
        OrderView view = orderAdmin.fulfill(id, status, carrier, trackingNumber);
        Map<String, Object> details = new HashMap<>();
        details.put("fulfillmentStatus", status);
        record("ORDER_FULFILLMENT", "order", id, details);
        return view;
    }

    /** Counts only. No revenue figures: no real payments exist. */
    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        return new Dashboard(catalog.countAll(), catalog.countByStatus(ProductStatus.ACTIVE),
                orderQueries.countRegistered(), orderQueries.countByStatus(OrderStatus.PENDING_PAYMENT));
    }

    private void record(String action, String entityType, UUID entityId, Map<String, Object> details) {
        audit.save(new AuditLog(CurrentUser.id().orElse(null), action, entityType, entityId, details));
    }
}
