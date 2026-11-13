package com.velora.cart.domain;

import com.velora.shared.domain.BusinessRuleException;
import com.velora.shared.domain.NotFoundException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "carts")
public class Cart {

    public static final int MAX_QUANTITY = 99;
    public static final int MAX_LINES = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "user_id")
    private UUID userId;
    @Column(name = "token_hash")
    private String tokenHash;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;
    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<CartItem> items = new ArrayList<>();
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;
    @Column(name = "checkout_revision", nullable = false)
    private long checkoutRevision;

    protected Cart() {
    }

    private Cart(UUID userId, String tokenHash, String currency) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.currency = currency;
    }

    public static Cart forUser(UUID userId, String currency) {
        return new Cart(userId, null, currency);
    }

    public static Cart forGuest(String tokenHash, String currency) {
        return new Cart(null, tokenHash, currency);
    }

    /** Adds quantity to the line for this variant (merging), enforcing per-line limits. Returns the new quantity. */
    public CartItem add(UUID variantId, int quantity) {
        validateQuantity(quantity);
        Optional<CartItem> existing = items.stream().filter(i -> i.getVariantId().equals(variantId)).findFirst();
        if (existing.isPresent()) {
            existing.get().changeQuantity(Math.min(existing.get().getQuantity() + quantity, MAX_QUANTITY + 1));
            validateQuantity(existing.get().getQuantity());
            touch();
            return existing.get();
        }
        if (items.size() >= MAX_LINES) {
            throw new BusinessRuleException("Cart line limit reached");
        }
        CartItem item = new CartItem(this, variantId, quantity);
        items.add(item);
        touch();
        return item;
    }

    public CartItem changeQuantity(UUID itemId, int quantity) {
        validateQuantity(quantity);
        CartItem item = item(itemId);
        item.changeQuantity(quantity);
        touch();
        return item;
    }

    public void remove(UUID itemId) {
        items.remove(item(itemId));
        touch();
    }

    public void clear() {
        items.clear();
        touch();
    }

    public CartItem item(UUID itemId) {
        return items.stream().filter(i -> i.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Cart item not found"));
    }

    public static void validateQuantity(int quantity) {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new BusinessRuleException("Quantity must be between 1 and " + MAX_QUANTITY);
        }
    }

    private void touch() {
        checkoutRevision++;
        updatedAt = Instant.now();
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
    public long getCheckoutRevision() { return checkoutRevision; }
    public UUID getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public String getCurrency() { return currency; }
    public List<CartItem> getItems() { return items; }
    public Instant getUpdatedAt() { return updatedAt; }
}
