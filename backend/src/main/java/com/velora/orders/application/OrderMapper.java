package com.velora.orders.application;

import static com.velora.shared.domain.Money.display;

import com.velora.orders.application.OrderDtos.AddressView;
import com.velora.orders.application.OrderDtos.CustomerView;
import com.velora.orders.application.OrderDtos.OrderEventView;
import com.velora.orders.application.OrderDtos.OrderItemView;
import com.velora.orders.application.OrderDtos.OrderSummaryView;
import com.velora.orders.application.OrderDtos.OrderView;
import com.velora.orders.domain.Order;
import com.velora.orders.domain.OrderItem;
import java.util.List;

public final class OrderMapper {
    private OrderMapper() {
    }

    public static OrderView view(Order o, boolean includeEvents) {
        AddressView address = o.shippingAddress().map(a -> new AddressView(a.getRecipientName(), a.getLine1(),
                a.getLine2(), a.getCity(), a.getState(), a.getPostalCode(), a.getCountry(), a.getPhone())).orElse(null);
        List<OrderItemView> items = o.getItems().stream().map(i -> new OrderItemView(i.getProductId(),
                i.getVariantId(), i.getSku(), i.getProductName(), i.getProductSlug(), i.getVariantName(),
                i.getImageUrl(), display(i.getUnitPrice()), i.getQuantity(), display(i.getLineTotal()))).toList();
        List<OrderEventView> events = includeEvents ? o.getEvents().stream().map(e -> new OrderEventView(e.getType(),
                e.getFromStatus(), e.getToStatus(), e.getNote(), e.getCreatedAt())).toList() : null;
        return new OrderView(o.getId(), o.getOrderNumber(), o.getStatus(), o.getPaymentStatus(),
                new CustomerView(o.getEmail(), o.getCustomerFirstName(), o.getCustomerLastName(), o.getCustomerPhone()),
                address, o.getCurrency(), items, display(o.getSubtotal()), display(o.getShippingTotal()),
                display(o.getTotal()), o.getQuoteExpiresAt(), o.getPlacedAt(), o.getCancelledAt(), o.getCreatedAt(),
                events);
    }

    public static OrderSummaryView summary(Order o) {
        return new OrderSummaryView(o.getId(), o.getOrderNumber(), o.getStatus(), o.getEmail(),
                o.getCustomerFirstName() + " " + o.getCustomerLastName(), o.getCurrency(),
                display(o.getTotal()), o.getItems().stream().mapToInt(OrderItem::getQuantity).sum(), o.getPlacedAt(),
                o.getCreatedAt());
    }
}
