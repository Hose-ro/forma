package com.velora.catalog.domain;

import com.velora.shared.domain.BusinessRuleException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id")
    private Category category;
    @Column(nullable = false, unique = true)
    private String slug;
    @Column(nullable = false)
    private String name;
    private String description;
    private String material;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status = ProductStatus.DRAFT;
    @Column(nullable = false)
    private boolean featured;
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;
    @Column(name = "compare_at_price", precision = 19, scale = 4)
    private BigDecimal compareAtPrice;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<ProductVariant> variants = new ArrayList<>();
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<ProductImage> images = new ArrayList<>();
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected Product() {
    }

    public Product(Category category, String slug, String name, BigDecimal price, String currency) {
        this.category = category;
        this.slug = slug;
        this.name = name;
        this.price = price;
        this.currency = currency;
    }

    public void update(Category category, String slug, String name, String description, String material,
                       Boolean featured, BigDecimal price, BigDecimal compareAtPrice, boolean compareAtProvided) {
        if (category != null) this.category = category;
        if (slug != null) this.slug = slug;
        if (name != null) this.name = name.trim();
        if (description != null) this.description = description;
        if (material != null) this.material = material;
        if (featured != null) this.featured = featured;
        if (price != null) this.price = price;
        if (compareAtProvided) this.compareAtPrice = compareAtPrice;
        if (this.compareAtPrice != null && this.compareAtPrice.compareTo(this.price) <= 0) {
            throw new BusinessRuleException("compareAtPrice must be greater than price");
        }
    }

    public ProductVariant addVariant() {
        ProductVariant v = new ProductVariant(this, currency);
        variants.add(v);
        return v;
    }

    public Optional<ProductVariant> variant(UUID variantId) {
        return variants.stream().filter(v -> v.getId() != null && v.getId().equals(variantId)).findFirst();
    }

    public void replaceImages(List<String[]> urlAndAlt) {
        images.clear();
        for (int i = 0; i < urlAndAlt.size(); i++) {
            images.add(new ProductImage(this, urlAndAlt.get(i)[0], urlAndAlt.get(i)[1], i));
        }
    }

    public void changeStatus(ProductStatus next) {
        if (next == ProductStatus.ACTIVE && variants.stream().noneMatch(ProductVariant::isActive)) {
            throw new BusinessRuleException("An ACTIVE product requires at least one active variant");
        }
        this.status = next;
    }

    public void archive() {
        this.status = ProductStatus.ARCHIVED;
        this.featured = false;
    }

    public boolean isAvailable() {
        return status == ProductStatus.ACTIVE && variants.stream().anyMatch(v -> v.isActive() && v.getStock() > 0);
    }

    public Optional<String> primaryImageUrl() {
        return images.stream().min(Comparator.comparingInt(ProductImage::getPosition)).map(ProductImage::getUrl)
                .or(() -> variants.stream().map(ProductVariant::getImageUrl).filter(u -> u != null).findFirst());
    }

    @PrePersist
    void prePersist() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Category getCategory() { return category; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getMaterial() { return material; }
    public ProductStatus getStatus() { return status; }
    public boolean isFeatured() { return featured; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getCompareAtPrice() { return compareAtPrice; }
    public String getCurrency() { return currency; }
    public List<ProductVariant> getVariants() { return variants; }
    public List<ProductImage> getImages() { return images; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
