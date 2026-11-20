package com.olvenio.catalog.api;

import com.olvenio.catalog.application.CatalogDtos.CategoryView;
import com.olvenio.catalog.application.CatalogDtos.ProductDetailView;
import com.olvenio.catalog.application.CatalogDtos.ProductFilter;
import com.olvenio.catalog.application.CatalogDtos.ProductSummaryView;
import com.olvenio.catalog.application.CatalogQueryService;
import com.olvenio.shared.api.PageResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Catalog")
public class CatalogController {

    private final CatalogQueryService catalog;

    public CatalogController(CatalogQueryService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/products")
    public PageResponse<ProductSummaryView> products(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) List<UUID> categoryIds,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Boolean available,
            @Parameter(description = "Search by name") @RequestParam(required = false) String q,
            @Parameter(description = "featured | price_asc | price_desc | newest") @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return catalog.search(new ProductFilter(category, minPrice, maxPrice, available, q), sort, page, size, categoryIds);
    }

    @GetMapping("/products/{slug}")
    public ProductDetailView product(@PathVariable String slug) {
        return catalog.bySlug(slug);
    }

    @GetMapping("/categories")
    public List<CategoryView> categories() {
        return catalog.categories();
    }

    @GetMapping("/categories/{slug}/products")
    public PageResponse<ProductSummaryView> categoryProducts(
            @PathVariable String slug,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Boolean available,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return catalog.byCategory(slug, new ProductFilter(null, minPrice, maxPrice, available, q), sort, page, size);
    }
}
