package com.olvenio.catalog.infrastructure;

import com.olvenio.catalog.domain.Product;
import com.olvenio.catalog.domain.ProductStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {
    Optional<Product> findBySlugAndStatus(String slug, ProductStatus status);

    Optional<Product> findByIdAndStatus(UUID id, ProductStatus status);

    @Query(value = """
            SELECT p.* FROM product_favorites f JOIN products p ON p.id = f.product_id
            WHERE f.user_id = :userId AND p.status = 'ACTIVE'
            ORDER BY f.created_at DESC, f.product_id
            """, nativeQuery = true)
    List<Product> findFavorites(UUID userId);

    boolean existsBySlug(String slug);

    boolean existsByCategoryId(UUID categoryId);

    long countByStatus(ProductStatus status);

    @Query(value = "select exists(select 1 from order_items where product_id = :productId)", nativeQuery = true)
    boolean hasOrderHistory(UUID productId);
}
