package com.olvenio.orders.domain;

import com.olvenio.shared.domain.ConflictException;
import com.olvenio.shared.domain.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Order aggregate. A checkout quote is an Order in DRAFT with an expiry; confirming it moves it to
 * PENDING_PAYMENT exactly once (guarded by row lock + Idempotency-Key).
 */
@Entity(name = "SalesOrder")
@Table(name = "orders")
public class Order {

    public enum PlacementCheck { PROCEED, REPLAY }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "order_number")
    private String orderNumber;
    @Column(name = "user_id")
    private UUID userId;
    @Column(name = "cart_id")
    private UUID cartId;
    @Column(name = "cart_revision")
    private Long cartRevision;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;
    @Column(name = "payment_status", nullable = false)
    private String paymentStatus = "CREATED";
    @Column(nullable = false)
    private String email;
    @Column(name = "customer_first_name", nullable = false)
    private String customerFirstName;
    @Column(name = "customer_last_name", nullable = false)
    private String customerLastName;
    @Column(name = "customer_phone")
    private String customerPhone;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotal;
    @Column(name = "shipping_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal shippingTotal;
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal total;
    @Column(name = "access_token_hash", nullable = false)
    private String accessTokenHash;
    @Column(name = "idempotency_key")
    private String idempotencyKey;
    @Column(name = "quote_expires_at", nullable = false)
    private Instant quoteExpiresAt;
    @Column(name = "placed_at")
    private Instant placedAt;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    @Column(name = "fulfillment_status", nullable = false, length = 20)
    private String fulfillmentStatus = "UNFULFILLED";
    private String carrier;
    @Column(name = "tracking_number")
    private String trackingNumber;
    @Column(name = "shipped_at")
    private Instant shippedAt;
    @Column(name = "delivered_at")
    private Instant deliveredAt;
    @Column(name = "reservation_expires_at")
    private Instant reservationExpiresAt;
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<OrderItem> items = new ArrayList<>();
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderAddress> addresses = new ArrayList<>();
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("createdAt ASC")
    private List<OrderEvent> events = new ArrayList<>();
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected Order() {
    }

    public static Order quote(UUID userId, UUID cartId, String email, String firstName, String lastName,
                              String phone, String currency, String accessTokenHash, Instant expiresAt) {
        Order o = new Order();
        o.userId = userId;
        o.cartId = cartId;
        o.status = OrderStatus.DRAFT;
        o.email = email.trim().toLowerCase(java.util.Locale.ROOT);
        o.customerFirstName = firstName.trim();
        o.customerLastName = lastName.trim();
        o.customerPhone = phone;
        o.currency = currency;
        o.accessTokenHash = accessTokenHash;
        o.quoteExpiresAt = expiresAt;
        o.subtotal = o.shippingTotal = o.total = BigDecimal.ZERO;
        o.events.add(new OrderEvent(o, "QUOTED", null, OrderStatus.DRAFT, null, userId, Instant.now()));
        return o;
    }

    public void addItem(UUID productId, UUID variantId, String sku, String productName, String productSlug,
                        String variantName, String imageUrl, Money unitPrice, int quantity, Money lineTotal) {
        requireDraft();
        items.add(new OrderItem(this, productId, variantId, sku, productName, productSlug, variantName, imageUrl,
                unitPrice.amount(), quantity, lineTotal.rounded(), currency, items.size()));
    }

    public void bindCartRevision(long revision) {
        requireDraft();
        this.cartRevision = revision;
    }

    public void requireCartRevision(long revision) {
        if (cartRevision == null || cartRevision != revision)
            throw new ConflictException("Cart changed or was already checked out; request a new quote");
    }

    public void shipTo(String recipient, String line1, String line2, String city, String state, String postalCode,
                       String country, String phone) {
        requireDraft();
        addresses.removeIf(a -> a.getType() == OrderAddress.Type.SHIPPING);
        addresses.add(new OrderAddress(this, OrderAddress.Type.SHIPPING, recipient, line1, line2, city, state,
                postalCode, country, phone));
    }

    public void applyTotals(Money subtotal, Money shipping, Money total) {
        requireDraft();
        this.subtotal = subtotal.rounded();
        this.shippingTotal = shipping.rounded();
        this.total = total.rounded();
    }

    public boolean isExpired(Instant now) {
        return status == OrderStatus.DRAFT && now.isAfter(quoteExpiresAt);
    }

    /** Decides whether a confirmation request should proceed, is an idempotent replay, or must be rejected. */
    public PlacementCheck checkPlacement(String key, Instant now) {
        return switch (status) {
            case PENDING_PAYMENT -> {
                if (key.equals(idempotencyKey)) yield PlacementCheck.REPLAY;
                throw new ConflictException("This quote was already confirmed as an order");
            }
            case PAID, PAYMENT_REVIEW, REFUNDED, PARTIALLY_REFUNDED -> {
                if (key.equals(idempotencyKey)) yield PlacementCheck.REPLAY;
                throw new ConflictException("Order was already placed");
            }
            case CANCELLED -> throw new ConflictException("This quote was cancelled");
            case DRAFT -> {
                if (isExpired(now)) throw new ConflictException("Quote expired; request a new quote");
                if (items.isEmpty()) throw new ConflictException("Quote has no items");
                yield PlacementCheck.PROCEED;
            }
        };
    }

    public void place(String orderNumber, String key, Instant now, UUID actor) {
        if (checkPlacement(key, now) != PlacementCheck.PROCEED) return;
        this.orderNumber = orderNumber;
        this.idempotencyKey = key;
        this.placedAt = now;
        transition(OrderStatus.PENDING_PAYMENT, "PLACED", null, actor, now);
    }

    /** Stock stays reserved for the unpaid order until this instant; afterwards the reservation can be released. */
    public void reserveUntil(Instant expiresAt) {
        if (status != OrderStatus.PENDING_PAYMENT) throw new ConflictException("Only a pending order holds a reservation");
        this.reservationExpiresAt = expiresAt;
    }

    public Instant getReservationExpiresAt() { return reservationExpiresAt; }

    public String getPaymentStatus() { return paymentStatus; }

    /** Called exclusively after a provider resource has passed backend verification. */
    public void recordVerifiedPayment(String financialStatus, Instant now) {
        boolean newlyApproved = "APPROVED".equals(financialStatus) && !"APPROVED".equals(paymentStatus);
        this.paymentStatus = financialStatus;
        // Its stock was already returned: never revive the order, but leave an explicit trail for a manual refund.
        if (status == OrderStatus.CANCELLED && newlyApproved)
            events.add(new OrderEvent(this, "PAID_AFTER_CANCELLATION", status, status,
                    "Pago aprobado después de cancelar: requiere reembolso manual", null, now));
        if (status == OrderStatus.CANCELLED || status == OrderStatus.DRAFT) return;
        OrderStatus next = switch (financialStatus) {
            case "APPROVED" -> OrderStatus.PAID;
            case "REFUNDED" -> OrderStatus.REFUNDED;
            case "PARTIALLY_REFUNDED" -> OrderStatus.PARTIALLY_REFUNDED;
            case "UNKNOWN" -> OrderStatus.PAYMENT_REVIEW;
            default -> OrderStatus.PENDING_PAYMENT;
        };
        if (status != next) transition(next, "PAYMENT_VERIFIED", financialStatus, null, now);
    }

    public void cancel(String note, UUID actor, Instant now) {
        if (status != OrderStatus.DRAFT && status != OrderStatus.PENDING_PAYMENT)
            throw new ConflictException("A financial order cannot be cancelled manually");
        if (status == OrderStatus.CANCELLED) {
            throw new ConflictException("Order already cancelled");
        }
        this.cancelledAt = now;
        transition(OrderStatus.CANCELLED, "CANCELLED", note, actor, now);
    }

    private static final List<String> FULFILLMENT_FLOW = List.of("UNFULFILLED", "PREPARING", "SHIPPED", "DELIVERED");

    /**
     * Logistics state of a paid order. Legal moves: UNFULFILLED → PREPARING → SHIPPED → DELIVERED (preparing may be
     * skipped). Shipping requires carrier and tracking number. Repeating the current state is an idempotent no-op.
     *
     * @return false when the request repeated the current state and nothing changed
     */
    public boolean fulfill(String next, String carrier, String trackingNumber, UUID actor, Instant now) {
        if (status != OrderStatus.PAID) throw new ConflictException("Only paid orders can be fulfilled");
        int from = FULFILLMENT_FLOW.indexOf(fulfillmentStatus);
        int to = FULFILLMENT_FLOW.indexOf(next);
        if (to < 1) throw new ConflictException("Unknown fulfillment status");
        String c = blankToNull(carrier);
        String t = blankToNull(trackingNumber);
        if (to == from) {
            if ((c != null && !c.equals(this.carrier)) || (t != null && !t.equals(this.trackingNumber)))
                throw new ConflictException("Order is already " + next + "; use a fulfillment correction to change its tracking data");
            return false;
        }
        if (to < from) throw new ConflictException("Fulfillment cannot move backwards; use a fulfillment correction");
        if ("DELIVERED".equals(next) && !"SHIPPED".equals(fulfillmentStatus))
            throw new ConflictException("Only a shipped order can be marked as delivered");
        if ("SHIPPED".equals(next)) {
            if (c == null || t == null) throw new ConflictException("Carrier and tracking number are required to ship");
            this.carrier = c;
            this.trackingNumber = t;
            this.shippedAt = now;
        }
        if ("DELIVERED".equals(next)) this.deliveredAt = now;
        this.fulfillmentStatus = next;
        events.add(new OrderEvent(this, "FULFILLMENT_" + next, status, status, this.trackingNumber, actor, now));
        return true;
    }

    /**
     * Explicit, audited repair of a logistics mistake: fix the tracking data and/or step back to an earlier state.
     * It never advances the order; advancing goes through {@link #fulfill}.
     *
     * @return true when a shipped order's tracking data changed (the customer must be notified again)
     */
    public boolean correctFulfillment(String target, String carrier, String trackingNumber, String note, UUID actor,
                                      Instant now) {
        if (status != OrderStatus.PAID) throw new ConflictException("Only paid orders can be corrected");
        if (note == null || note.isBlank()) throw new ConflictException("A correction requires a reason");
        int from = FULFILLMENT_FLOW.indexOf(fulfillmentStatus);
        int to = target == null ? from : FULFILLMENT_FLOW.indexOf(target);
        if (to < 0 || to > from) throw new ConflictException("A correction can only keep or step back the fulfillment status");
        String c = blankToNull(carrier);
        String t = blankToNull(trackingNumber);
        boolean shippedAfter = to >= FULFILLMENT_FLOW.indexOf("SHIPPED");
        boolean trackingChanged = false;
        if (shippedAfter) {
            trackingChanged = (c != null && !c.equals(this.carrier)) || (t != null && !t.equals(this.trackingNumber));
            if (c != null) this.carrier = c;
            if (t != null) this.trackingNumber = t;
            if (this.carrier == null || this.trackingNumber == null)
                throw new ConflictException("Carrier and tracking number are required for a shipped order");
        } else {
            this.carrier = null;
            this.trackingNumber = null;
            this.shippedAt = null;
        }
        if (to < FULFILLMENT_FLOW.indexOf("DELIVERED")) this.deliveredAt = null;
        if (to == from && !trackingChanged) throw new ConflictException("The correction does not change anything");
        this.fulfillmentStatus = FULFILLMENT_FLOW.get(to);
        events.add(new OrderEvent(this, "FULFILLMENT_CORRECTED", status, status,
                (fulfillmentStatus + ": " + note.trim()), actor, now));
        return trackingChanged;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void transition(OrderStatus to, String type, String note, UUID actor, Instant now) {
        events.add(new OrderEvent(this, type, status, to, note, actor, now));
        this.status = to;
    }

    private void requireDraft() {
        if (status != OrderStatus.DRAFT) throw new ConflictException("Order is no longer editable");
    }

    public Optional<OrderAddress> shippingAddress() {
        return addresses.stream().filter(a -> a.getType() == OrderAddress.Type.SHIPPING).findFirst();
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
    public String getFulfillmentStatus() { return fulfillmentStatus; }
    public String getCarrier() { return carrier; }
    public String getTrackingNumber() { return trackingNumber; }
    public Instant getShippedAt() { return shippedAt; }
    public String getOrderNumber() { return orderNumber; }
    public UUID getUserId() { return userId; }
    public UUID getCartId() { return cartId; }
    public OrderStatus getStatus() { return status; }
    public String getEmail() { return email; }
    public String getCustomerFirstName() { return customerFirstName; }
    public String getCustomerLastName() { return customerLastName; }
    public String getCustomerPhone() { return customerPhone; }
    public String getCurrency() { return currency; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getShippingTotal() { return shippingTotal; }
    public BigDecimal getTotal() { return total; }
    public String getAccessTokenHash() { return accessTokenHash; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getQuoteExpiresAt() { return quoteExpiresAt; }
    public Instant getPlacedAt() { return placedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public List<OrderItem> getItems() { return items; }
    public List<OrderEvent> getEvents() { return events; }
    public Instant getCreatedAt() { return createdAt; }
}
