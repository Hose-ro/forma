package com.velora.catalog.infrastructure;

import com.velora.catalog.domain.Product;
import com.velora.catalog.domain.ProductStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {
    Optional<Product> findBySlugAndStatus(String slug, ProductStatus status);

    boolean existsBySlug(String slug);

    boolean existsByCategoryId(UUID categoryId);

    long countByStatus(ProductStatus status);

    @Query(value = "select exists(select 1 from order_items where product_id = :productId)", nativeQuery = true)
    boolean hasOrderHistory(UUID productId);
}
