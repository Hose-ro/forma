package com.olvenio.admin.application;

import com.olvenio.admin.domain.AuditLog;
import com.olvenio.admin.infrastructure.AuditLogRepository;
import com.olvenio.catalog.application.CatalogAdminService;
import com.olvenio.catalog.application.CatalogDtos.AdminProductView;
import com.olvenio.catalog.application.CatalogDtos.CategoryCreateRequest;
import com.olvenio.catalog.application.CatalogDtos.CategoryPatchRequest;
import com.olvenio.catalog.application.CatalogDtos.CategoryView;
import com.olvenio.catalog.application.CatalogDtos.DeleteResult;
import com.olvenio.catalog.application.CatalogDtos.ProductCreateRequest;
import com.olvenio.catalog.application.CatalogDtos.ProductPatchRequest;
import com.olvenio.catalog.domain.ProductStatus;
import com.olvenio.orders.application.OrderAdminService;
import com.olvenio.orders.application.OrderDtos.OrderView;
import com.olvenio.orders.application.OrderQueryService;
import com.olvenio.orders.domain.OrderStatus;
import com.olvenio.shared.security.CurrentUser;
import com.olvenio.storefront.application.HomeHeroDtos.HeroView;
import com.olvenio.storefront.application.HomeHeroService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin use cases: delegates to module services and records an audit trail in the same transaction. */
@Service
public class AdminService {

    public record CategoryUnits(String name, long units) {
    }

    public record Dashboard(long totalProducts, long activeProducts, long registeredOrders, long pendingOrders,
                            java.util.List<CategoryUnits> unitsByCategory) {
    }

    private final CatalogAdminService catalog;
    private final OrderQueryService orderQueries;
    private final OrderAdminService orderAdmin;
    private final AuditLogRepository audit;
    private final org.springframework.jdbc.core.JdbcTemplate db;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final HomeHeroService heroes;

    public AdminService(CatalogAdminService catalog, OrderQueryService orderQueries, OrderAdminService orderAdmin,
                        AuditLogRepository audit, org.springframework.jdbc.core.JdbcTemplate db,
                        org.springframework.transaction.support.TransactionTemplate tx, HomeHeroService heroes) {
        this.db = db;
        this.tx = tx;
        this.heroes = heroes;
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
    public AdminProductView adjustStock(UUID variantId, int stock, int expectedStock) {
        AdminProductView view = catalog.adjustStock(variantId, stock, expectedStock);
        record("STOCK_ADJUSTED", "variant", variantId, Map.of("from", expectedStock, "to", stock));
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

    /** Provider calls happen first, outside the transaction; cancellation, restock and audit commit together. */
    public OrderView cancelOrder(UUID id, String note) {
        orderAdmin.prepareCancel(id);
        return tx.execute(t -> {
            OrderView view = orderAdmin.cancel(id, note);
            Map<String, Object> details = new HashMap<>();
            if (note != null) details.put("note", note);
            record("ORDER_CANCELLED", "order", id, details);
            return view;
        });
    }

    @Transactional
    public OrderView correctFulfillment(UUID id, String status, String carrier, String trackingNumber, String note) {
        OrderView view = orderAdmin.correctFulfillment(id, status, carrier, trackingNumber, note);
        Map<String, Object> details = new HashMap<>();
        details.put("fulfillmentStatus", view.fulfillmentStatus());
        details.put("note", note);
        record("ORDER_FULFILLMENT_CORRECTED", "order", id, details);
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

    public java.util.List<HeroView> heroes() {
        return heroes.all();
    }

    @Transactional
    public HeroView replaceHero(String variant, boolean enabled, Object content) {
        HeroView view = heroes.replace(variant, enabled, content);
        record("HOME_HERO_UPDATED", "home_hero", null, Map.of("variant", variant, "enabled", enabled));
        return view;
    }

    /** Counts only. No revenue figures: no real payments exist. */
    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        // Aggregated in the database: the admin lists are paginated and must not be summed client-side.
        var units = db.query("""
                select c.name, coalesce(sum(v.stock) filter (where p.status<>'ARCHIVED' and v.active), 0) as units
                from categories c left join products p on p.category_id=c.id
                    left join product_variants v on v.product_id=p.id
                group by c.id, c.name, c.sort_order order by c.sort_order, c.name""",
                (r, i) -> new CategoryUnits(r.getString("name"), r.getLong("units")));
        return new Dashboard(catalog.countAll(), catalog.countByStatus(ProductStatus.ACTIVE),
                orderQueries.countRegistered(), orderQueries.countByStatus(OrderStatus.PENDING_PAYMENT), units);
    }

    private void record(String action, String entityType, UUID entityId, Map<String, Object> details) {
        audit.save(new AuditLog(CurrentUser.id().orElse(null), action, entityType, entityId, details));
    }
}
