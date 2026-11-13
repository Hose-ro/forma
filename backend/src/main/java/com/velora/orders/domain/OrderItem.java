package com.velora.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable snapshot of what was sold: catalog changes never alter historical amounts. */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;
    @Column(name = "product_id")
    private UUID productId;
    @Column(name = "variant_id")
    private UUID variantId;
    @Column(nullable = false, updatable = false)
    private String sku;
    @Column(name = "product_name", nullable = false, updatable = false)
    private String productName;
    @Column(name = "product_slug", nullable = false, updatable = false)
    private String productSlug;
    @Column(name = "variant_name", nullable = false, updatable = false)
    private String variantName;
    @Column(name = "image_url", updatable = false)
    private String imageUrl;
    @Column(name = "unit_price", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;
    @Column(nullable = false, updatable = false)
    private int quantity;
    @Column(name = "line_total", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal lineTotal;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;
    @Column(nullable = false, updatable = false)
    private int position;

    protected OrderItem() {
    }

    OrderItem(Order order, UUID productId, UUID variantId, String sku, String productName, String productSlug,
              String variantName, String imageUrl, BigDecimal unitPrice, int quantity, BigDecimal lineTotal,
              String currency, int position) {
        this.order = order;
        this.productId = productId;
        this.variantId = variantId;
        this.sku = sku;
        this.productName = productName;
        this.productSlug = productSlug;
        this.variantName = variantName;
        this.imageUrl = imageUrl;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.lineTotal = lineTotal;
        this.currency = currency;
        this.position = position;
    }

    public UUID getId() { return id; }
    public UUID getProductId() { return productId; }
    public UUID getVariantId() { return variantId; }
    public String getSku() { return sku; }
    public String getProductName() { return productName; }
    public String getProductSlug() { return productSlug; }
    public String getVariantName() { return variantName; }
    public String getImageUrl() { return imageUrl; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public int getQuantity() { return quantity; }
    public BigDecimal getLineTotal() { return lineTotal; }
    public String getCurrency() { return currency; }
    public int getPosition() { return position; }
}
