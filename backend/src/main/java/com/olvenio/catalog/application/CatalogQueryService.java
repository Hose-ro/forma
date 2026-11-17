package com.olvenio.catalog.application;

import static com.olvenio.catalog.infrastructure.ProductSpecifications.available;
import static com.olvenio.catalog.infrastructure.ProductSpecifications.hasStatus;
import static com.olvenio.catalog.infrastructure.ProductSpecifications.inCategory;
import static com.olvenio.catalog.infrastructure.ProductSpecifications.nameContains;
import static com.olvenio.catalog.infrastructure.ProductSpecifications.priceAtLeast;
import static com.olvenio.catalog.infrastructure.ProductSpecifications.priceAtMost;

import com.olvenio.catalog.application.CatalogDtos.CategoryView;
import com.olvenio.catalog.application.CatalogDtos.ProductDetailView;
import com.olvenio.catalog.application.CatalogDtos.ProductFilter;
import com.olvenio.catalog.application.CatalogDtos.ProductSummaryView;
import com.olvenio.catalog.domain.ProductSort;
import com.olvenio.catalog.domain.ProductStatus;
import com.olvenio.catalog.infrastructure.CategoryRepository;
import com.olvenio.catalog.infrastructure.ProductRepository;
import com.olvenio.shared.api.PageRequests;
import com.olvenio.shared.api.PageResponse;
import com.olvenio.shared.domain.InvalidRequestException;
import com.olvenio.shared.domain.NotFoundException;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public catalog: only ACTIVE products are ever exposed. */
@Service
@Transactional(readOnly = true)
public class CatalogQueryService {

    private final ProductRepository products;
    private final CategoryRepository categories;

    public CatalogQueryService(ProductRepository products, CategoryRepository categories) {
        this.products = products;
        this.categories = categories;
    }

    public PageResponse<ProductSummaryView> search(ProductFilter f, String sort, Integer page, Integer size) {
        validateFilters(f);
        if (f.minPrice() != null && f.maxPrice() != null && f.minPrice().compareTo(f.maxPrice()) > 0) {
            throw new InvalidRequestException("minPrice must be less than or equal to maxPrice");
        }
        if (f.q() != null && f.q().length() > 100) {
            throw new InvalidRequestException("q is too long");
        }
        Specification<com.olvenio.catalog.domain.Product> spec = Specification.allOf(
                hasStatus(ProductStatus.ACTIVE), inCategory(f.category()), priceAtLeast(f.minPrice()),
                priceAtMost(f.maxPrice()), available(f.available()), nameContains(f.q()));
        var pageable = PageRequests.of(page, size, ProductSort.parse(sort).sort());
        return PageResponse.of(products.findAll(spec, pageable), CatalogMapper::summary);
    }

    public static void validateFilters(ProductFilter f) {
        if (f.category() != null && (f.category().length() > 120 || !f.category().matches(CatalogDtos.SLUG)))
            throw new InvalidRequestException("Invalid category slug");
        for (java.math.BigDecimal price : new java.math.BigDecimal[]{f.minPrice(), f.maxPrice()}) {
            if (price != null && (price.signum() < 0 || price.scale() > 4 || price.precision() - price.scale() > 15))
                throw new InvalidRequestException("Invalid price filter");
        }
        if (f.q() != null && f.q().length() > 100) throw new InvalidRequestException("q is too long");
    }

    public ProductDetailView bySlug(String slug) {
        validateSlug(slug);
        return products.findBySlugAndStatus(slug, ProductStatus.ACTIVE).map(CatalogMapper::detail)
                .orElseThrow(() -> new NotFoundException("Product not found"));
    }

    public List<CategoryView> categories() {
        return categories.findAllByOrderBySortOrderAscNameAsc().stream().map(CatalogMapper::category).toList();
    }

    public PageResponse<ProductSummaryView> byCategory(String slug, ProductFilter f, String sort, Integer page,
                                                       Integer size) {
        validateSlug(slug);
        if (!categories.existsBySlug(slug)) {
            throw new NotFoundException("Category not found");
        }
        return search(new ProductFilter(slug, f.minPrice(), f.maxPrice(), f.available(), f.q()), sort, page, size);
    }

    private static void validateSlug(String slug) {
        if (slug.length() > 160 || !slug.matches(CatalogDtos.SLUG))
            throw new InvalidRequestException("Invalid slug");
    }
}
