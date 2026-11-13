package com.velora.catalog.infrastructure;

import com.velora.catalog.domain.Product;
import com.velora.catalog.domain.ProductStatus;
import com.velora.catalog.domain.ProductVariant;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

public final class ProductSpecifications {
    private ProductSpecifications() {
    }

    public static Specification<Product> hasStatus(ProductStatus status) {
        return (root, q, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    public static Specification<Product> inCategory(String categorySlug) {
        return (root, q, cb) -> categorySlug == null || categorySlug.isBlank() ? null
                : cb.equal(root.get("category").get("slug"), categorySlug);
    }

    public static Specification<Product> priceAtLeast(BigDecimal min) {
        return (root, q, cb) -> min == null ? null : cb.greaterThanOrEqualTo(root.get("price"), min);
    }

    public static Specification<Product> priceAtMost(BigDecimal max) {
        return (root, q, cb) -> max == null ? null : cb.lessThanOrEqualTo(root.get("price"), max);
    }

    public static Specification<Product> nameContains(String text) {
        return (root, q, cb) -> {
            if (text == null || text.isBlank()) return null;
            String escaped = text.trim().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            return cb.like(cb.lower(root.get("name")), "%" + escaped + "%", '\\');
        };
    }

    public static Specification<Product> available(Boolean available) {
        return (root, q, cb) -> {
            if (available == null) return null;
            Subquery<Integer> sub = q.subquery(Integer.class);
            var v = sub.from(ProductVariant.class);
            sub.select(cb.literal(1)).where(cb.equal(v.get("product"), root), cb.isTrue(v.get("active")),
                    cb.greaterThan(v.get("stock"), 0));
            return available ? cb.exists(sub) : cb.not(cb.exists(sub));
        };
    }
}
