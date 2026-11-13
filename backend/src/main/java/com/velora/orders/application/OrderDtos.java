package com.velora.orders.application;

import com.velora.orders.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {
    private OrderDtos() {
    }


    public record CustomerView(String email, String firstName, String lastName, String phone) {
    }

    public record AddressView(String recipientName, String line1, String line2, String city, String state,
                              String postalCode, String country, String phone) {
    }

    public record OrderItemView(UUID productId, UUID variantId, String sku, String productName, String productSlug,
                                String variantName, String imageUrl, BigDecimal unitPrice, int quantity,
                                BigDecimal lineTotal) {
    }

    public record OrderEventView(String type, OrderStatus fromStatus, OrderStatus toStatus, String note,
                                 Instant createdAt) {
    }

    public record OrderView(UUID id, String orderNumber, OrderStatus status, String paymentStatus,
                            CustomerView customer, AddressView shippingAddress, String currency,
                            List<OrderItemView> items, BigDecimal subtotal, BigDecimal shippingTotal,
                            BigDecimal total, Instant quoteExpiresAt, Instant placedAt, Instant cancelledAt,
                            Instant createdAt, List<OrderEventView> events, String fulfillmentStatus,
                            String carrier, String trackingNumber, Instant shippedAt) {
    }

    public record OrderSummaryView(UUID id, String orderNumber, OrderStatus status, String email, String customerName,
                                   String currency,
                                   BigDecimal total, int itemCount, Instant placedAt, Instant createdAt,
                                   String fulfillmentStatus) {
    }
}
