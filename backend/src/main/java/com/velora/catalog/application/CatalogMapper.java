package com.velora.catalog.application;

import static com.velora.shared.domain.Money.display;

import com.velora.catalog.application.CatalogDtos.AdminProductView;
import com.velora.catalog.application.CatalogDtos.AdminVariantView;
import com.velora.catalog.application.CatalogDtos.CategoryRef;
import com.velora.catalog.application.CatalogDtos.CategoryView;
import com.velora.catalog.application.CatalogDtos.ImageView;
import com.velora.catalog.application.CatalogDtos.ProductDetailView;
import com.velora.catalog.application.CatalogDtos.ProductSummaryView;
import com.velora.catalog.application.CatalogDtos.VariantView;
import com.velora.catalog.domain.Category;
import com.velora.catalog.domain.Product;
import com.velora.catalog.domain.ProductVariant;
import java.util.List;

final class CatalogMapper {
    private CatalogMapper() {
    }

    static CategoryView category(Category c) {
        return new CategoryView(c.getId(), c.getSlug(), c.getName(), c.getDescription(), c.getImageUrl(),
                c.getSortOrder());
    }

    static ProductSummaryView summary(Product p) {
        return new ProductSummaryView(p.getId(), p.getSlug(), p.getName(), ref(p), display(p.getPrice()),
                display(p.getCompareAtPrice()), p.getCurrency(), p.isFeatured(), p.isAvailable(),
                p.primaryImageUrl().orElse(null),
                p.getVariants().stream().filter(v -> v.isPurchasable(1)).map(ProductVariant::getId).findFirst()
                        .orElse(null),
                p.getCreatedAt());
    }

    static ProductDetailView detail(Product p) {
        List<VariantView> variants = p.getVariants().stream().filter(ProductVariant::isActive)
                .map(v -> new VariantView(v.getId(), v.getSku(), v.getName(), v.getColor(), display(v.getPrice()),
                        display(v.getCompareAtPrice()), v.getCurrency(), v.getStock(), v.isPurchasable(1),
                        v.getImageUrl()))
                .toList();
        return new ProductDetailView(p.getId(), p.getSlug(), p.getName(), p.getDescription(), p.getMaterial(),
                ref(p), display(p.getPrice()), display(p.getCompareAtPrice()), p.getCurrency(), p.isFeatured(),
                p.isAvailable(), images(p), variants, p.getCreatedAt());
    }

    static AdminProductView admin(Product p) {
        List<AdminVariantView> variants = p.getVariants().stream()
                .map(v -> new AdminVariantView(v.getId(), v.getSku(), v.getName(), v.getColor(),
                        display(v.getPrice()), display(v.getCompareAtPrice()), v.getCurrency(), v.getStock(),
                        v.isActive(), v.getPosition(), v.getImageUrl()))
                .toList();
        return new AdminProductView(p.getId(), p.getSlug(), p.getName(), p.getDescription(), p.getMaterial(),
                p.getCategory().getId(), p.getCategory().getSlug(), p.getStatus(), p.isFeatured(), p.isAvailable(),
                display(p.getPrice()), display(p.getCompareAtPrice()), p.getCurrency(), images(p), variants,
                p.getCreatedAt(), p.getUpdatedAt());
    }

    private static CategoryRef ref(Product p) {
        return new CategoryRef(p.getCategory().getSlug(), p.getCategory().getName());
    }

    private static List<ImageView> images(Product p) {
        return p.getImages().stream().map(i -> new ImageView(i.getUrl(), i.getAltText())).toList();
    }
}
