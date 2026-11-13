package com.velora.catalog.domain;

import com.velora.shared.domain.BusinessRuleException;
import com.velora.shared.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "product_variants")
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;
    @Column(nullable = false, unique = true)
    private String sku;
    @Column(nullable = false)
    private String name;
    private String color;
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;
    @Column(name = "compare_at_price", precision = 19, scale = 4)
    private BigDecimal compareAtPrice;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(nullable = false)
    private int stock;
    @Column(nullable = false)
    private boolean active = true;
    @Column(nullable = false)
    private int position;
    @Column(name = "image_url")
    private String imageUrl;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected ProductVariant() {
    }

    ProductVariant(Product product, String currency) {
        this.product = product;
        this.currency = currency;
    }

    public void update(String sku, String name, String color, BigDecimal price, BigDecimal compareAtPrice,
                       Integer stock, Boolean active, Integer position, String imageUrl) {
        if (sku != null) this.sku = sku.trim().toUpperCase();
        if (name != null) this.name = name.trim();
        this.color = color;
        if (price != null) this.price = price;
        this.compareAtPrice = compareAtPrice;
        if (stock != null) {
            if (stock < 0) throw new BusinessRuleException("Stock cannot be negative");
            this.stock = stock;
        }
        if (active != null) this.active = active;
        if (position != null) this.position = position;
        this.imageUrl = imageUrl;
        if (this.compareAtPrice != null && this.compareAtPrice.compareTo(this.price) <= 0) {
            throw new BusinessRuleException("Variant compareAtPrice must be greater than price");
        }
    }

    public void deactivate() {
        this.active = false;
    }

    /** Purchasable now: variant active, product published and enough stock. */
    public boolean isPurchasable(int quantity) {
        return active && product.getStatus() == ProductStatus.ACTIVE && stock >= quantity;
    }

    public void decreaseStock(int quantity) {
        if (quantity <= 0 || stock < quantity) {
            throw new BusinessRuleException("Insufficient stock for " + sku);
        }
        stock -= quantity;
    }

    public void increaseStock(int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity");
        stock += quantity;
    }

    public Money unitPrice() {
        return Money.of(price, currency);
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
    public Product getProduct() { return product; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getColor() { return color; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getCompareAtPrice() { return compareAtPrice; }
    public String getCurrency() { return currency; }
    public int getStock() { return stock; }
    public boolean isActive() { return active; }
    public int getPosition() { return position; }
    public String getImageUrl() { return imageUrl; }
}
