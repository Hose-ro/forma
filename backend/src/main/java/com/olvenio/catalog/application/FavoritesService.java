package com.olvenio.catalog.application;

import com.olvenio.catalog.application.CatalogDtos.ProductSummaryView;
import com.olvenio.catalog.domain.ProductStatus;
import com.olvenio.catalog.infrastructure.ProductRepository;
import com.olvenio.shared.domain.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FavoritesService {
    private final ProductRepository products;
    private final JdbcTemplate db;

    public FavoritesService(ProductRepository products, JdbcTemplate db) {
        this.products = products;
        this.db = db;
    }

    @Transactional(readOnly = true)
    public List<ProductSummaryView> list(UUID userId) {
        return products.findFavorites(userId).stream().map(CatalogMapper::summary).toList();
    }

    @Transactional
    public ProductSummaryView add(UUID userId, UUID productId) {
        var product = products.findByIdAndStatus(productId, ProductStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException("Product not found"));
        db.update("""
                INSERT INTO product_favorites (user_id, product_id) VALUES (?, ?)
                ON CONFLICT (user_id, product_id) DO NOTHING
                """, userId, productId);
        return CatalogMapper.summary(product);
    }

    @Transactional
    public void remove(UUID userId, UUID productId) {
        db.update("DELETE FROM product_favorites WHERE user_id = ? AND product_id = ?", userId, productId);
    }
}
