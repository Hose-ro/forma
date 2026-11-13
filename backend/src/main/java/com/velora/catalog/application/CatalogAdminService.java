package com.velora.catalog.application;

import static com.velora.catalog.infrastructure.ProductSpecifications.hasStatus;
import static com.velora.catalog.infrastructure.ProductSpecifications.inCategory;
import static com.velora.catalog.infrastructure.ProductSpecifications.nameContains;

import com.velora.catalog.application.CatalogDtos.AdminProductView;
import com.velora.catalog.application.CatalogDtos.CategoryCreateRequest;
import com.velora.catalog.application.CatalogDtos.CategoryPatchRequest;
import com.velora.catalog.application.CatalogDtos.CategoryView;
import com.velora.catalog.application.CatalogDtos.DeleteOutcome;
import com.velora.catalog.application.CatalogDtos.DeleteResult;
import com.velora.catalog.application.CatalogDtos.ImageInput;
import com.velora.catalog.application.CatalogDtos.ProductCreateRequest;
import com.velora.catalog.application.CatalogDtos.ProductPatchRequest;
import com.velora.catalog.application.CatalogDtos.VariantInput;
import com.velora.catalog.domain.Category;
import com.velora.catalog.domain.Product;
import com.velora.catalog.domain.ProductStatus;
import com.velora.catalog.domain.ProductVariant;
import com.velora.catalog.infrastructure.CategoryRepository;
import com.velora.catalog.infrastructure.ProductRepository;
import com.velora.catalog.infrastructure.ProductVariantRepository;
import com.velora.shared.api.PageRequests;
import com.velora.shared.api.PageResponse;
import com.velora.shared.config.AppProperties;
import com.velora.shared.domain.BusinessRuleException;
import com.velora.shared.domain.ConflictException;
import com.velora.shared.domain.NotFoundException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CatalogAdminService {

    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final CategoryRepository categories;
    private final String currency;

    public CatalogAdminService(ProductRepository products, ProductVariantRepository variants,
                               CategoryRepository categories, AppProperties props) {
        this.products = products;
        this.variants = variants;
        this.categories = categories;
        this.currency = props.currency();
    }

    // ---------- Products ----------

    @Transactional(readOnly = true)
    public PageResponse<AdminProductView> list(ProductStatus status, String category, String q, Integer page,
                                               Integer size) {
        CatalogQueryService.validateFilters(new CatalogDtos.ProductFilter(category, null, null, null, q));
        var spec = Specification.allOf(hasStatus(status), inCategory(category), nameContains(q));
        var pageable = PageRequests.of(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("id")));
        return PageResponse.of(products.findAll(spec, pageable), CatalogMapper::admin);
    }

    @Transactional(readOnly = true)
    public AdminProductView get(UUID id) {
        return CatalogMapper.admin(load(id));
    }

    public AdminProductView create(ProductCreateRequest req) {
        if (products.existsBySlug(req.slug())) {
            throw new ConflictException("Slug already in use");
        }
        Product p = new Product(category(req.categoryId()), req.slug(), req.name().trim(), req.price(), currency);
        p.update(null, null, null, req.description(), req.material(), req.featured(), null, req.compareAtPrice(),
                true);
        applyVariants(p, req.variants());
        applyImages(p, req.images());
        p.changeStatus(req.status() == null ? ProductStatus.DRAFT : req.status());
        return CatalogMapper.admin(products.saveAndFlush(p));
    }

    public AdminProductView update(UUID id, ProductPatchRequest req) {
        Product p = load(id);
        if (req.slug() != null && !req.slug().equals(p.getSlug()) && products.existsBySlug(req.slug())) {
            throw new ConflictException("Slug already in use");
        }
        boolean compareProvided = req.compareAtPrice() != null || Boolean.TRUE.equals(req.clearCompareAtPrice());
        p.update(req.categoryId() == null ? null : category(req.categoryId()), req.slug(), req.name(),
                req.description(), req.material(), req.featured(), req.price(),
                Boolean.TRUE.equals(req.clearCompareAtPrice()) ? null : req.compareAtPrice(), compareProvided);
        if (req.variants() != null) applyVariants(p, req.variants());
        if (req.images() != null) applyImages(p, req.images());
        p.changeStatus(req.status() == null ? p.getStatus() : req.status());
        return CatalogMapper.admin(products.saveAndFlush(p));
    }

    /** Hard-deletes only products without order history; otherwise archives to preserve references. */
    public DeleteResult delete(UUID id) {
        Product p = load(id);
        if (products.hasOrderHistory(id)) {
            p.archive();
            return new DeleteResult(id, DeleteOutcome.ARCHIVED);
        }
        products.delete(p);
        return new DeleteResult(id, DeleteOutcome.DELETED);
    }

    @Transactional(readOnly = true)
    public long countAll() {
        return products.count();
    }

    @Transactional(readOnly = true)
    public long countByStatus(ProductStatus status) {
        return products.countByStatus(status);
    }

    private void applyVariants(Product p, List<VariantInput> inputs) {
        Set<String> skusInRequest = new HashSet<>();
        Set<UUID> kept = new HashSet<>();
        for (VariantInput in : inputs) {
            String sku = in.sku().trim().toUpperCase(Locale.ROOT);
            if (!skusInRequest.add(sku)) {
                throw new BusinessRuleException("Duplicate SKU in request: " + sku);
            }
            ProductVariant v;
            if (in.id() != null) {
                v = p.variant(in.id()).orElseThrow(() -> new NotFoundException("Variant not found: " + in.id()));
                kept.add(in.id());
                if (!sku.equalsIgnoreCase(v.getSku()) && variants.existsBySkuIgnoreCase(sku)) {
                    throw new ConflictException("SKU already in use: " + sku);
                }
            } else {
                if (variants.existsBySkuIgnoreCase(sku)) {
                    throw new ConflictException("SKU already in use: " + sku);
                }
                v = p.addVariant();
            }
            v.update(sku, in.name(), in.color(), in.price(), in.compareAtPrice(), in.stock(),
                    in.active() == null ? Boolean.TRUE : in.active(), in.position(), in.imageUrl());
        }
        // Variants omitted from the request are deactivated (never deleted: carts/orders may reference them).
        p.getVariants().stream().filter(v -> v.getId() != null && !kept.contains(v.getId()))
                .forEach(ProductVariant::deactivate);
    }

    private static void applyImages(Product p, List<ImageInput> images) {
        if (images == null) return;
        p.replaceImages(images.stream().map(i -> new String[]{i.url(), i.altText()}).toList());
    }

    private Product load(UUID id) {
        return products.findById(id).orElseThrow(() -> new NotFoundException("Product not found"));
    }

    // ---------- Categories ----------

    @Transactional(readOnly = true)
    public List<CategoryView> categories() {
        return categories.findAllByOrderBySortOrderAscNameAsc().stream().map(CatalogMapper::category).toList();
    }

    public CategoryView createCategory(CategoryCreateRequest req) {
        if (categories.existsBySlug(req.slug())) {
            throw new ConflictException("Slug already in use");
        }
        Category c = new Category(req.slug(), req.name(), req.description(), req.imageUrl(),
                req.sortOrder() == null ? 0 : req.sortOrder());
        return CatalogMapper.category(categories.saveAndFlush(c));
    }

    public CategoryView updateCategory(UUID id, CategoryPatchRequest req) {
        Category c = category(id);
        if (req.slug() != null && !req.slug().equals(c.getSlug()) && categories.existsBySlug(req.slug())) {
            throw new ConflictException("Slug already in use");
        }
        c.update(req.slug(), req.name(), req.description(), req.imageUrl(), req.sortOrder());
        return CatalogMapper.category(categories.saveAndFlush(c));
    }

    public void deleteCategory(UUID id) {
        Category c = category(id);
        if (products.existsByCategoryId(id)) {
            throw new ConflictException("Category has products; move or archive them first");
        }
        categories.delete(c);
    }

    private Category category(UUID id) {
        return categories.findById(id).orElseThrow(() -> new NotFoundException("Category not found"));
    }
}
