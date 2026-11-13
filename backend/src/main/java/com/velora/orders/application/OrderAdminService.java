package com.velora.orders.application;

import com.velora.catalog.domain.ProductVariant;
import com.velora.catalog.infrastructure.ProductVariantRepository;
import com.velora.orders.application.OrderDtos.OrderView;
import com.velora.orders.domain.Order;
import com.velora.orders.domain.OrderItem;
import com.velora.orders.domain.OrderStatus;
import com.velora.orders.infrastructure.OrderRepository;
import com.velora.shared.domain.NotFoundException;
import com.velora.shared.security.CurrentUser;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderAdminService {

    private final OrderRepository orders;
    private final ProductVariantRepository variants;
    private final com.velora.payments.infrastructure.PaymentRepository payments;

    public OrderAdminService(OrderRepository orders, ProductVariantRepository variants, com.velora.payments.infrastructure.PaymentRepository payments) {
        this.orders = orders;
        this.variants = variants;
        this.payments = payments;
    }

    /** Cancels a quote or pending order; reserved stock of a PENDING_PAYMENT order is returned. */
    @Transactional
    public OrderView cancel(UUID id, String note) {
        Order order = orders.lockById(id).orElseThrow(() -> new NotFoundException("Order not found"));
        if (payments.byOrder(id).isPresent()) throw new com.velora.shared.domain.ConflictException("Payment has been initiated; reconcile it before cancellation. Provider cancellation is not enabled.");
        boolean restock = order.getStatus() == OrderStatus.PENDING_PAYMENT;
        order.cancel(note, CurrentUser.id().orElse(null), Instant.now());
        if (restock) {
            var ids = order.getItems().stream().map(OrderItem::getVariantId).filter(Objects::nonNull).toList();
            Map<UUID, ProductVariant> locked = variants.lockAllById(ids).stream()
                    .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));
            for (OrderItem item : order.getItems()) {
                ProductVariant v = item.getVariantId() == null ? null : locked.get(item.getVariantId());
                if (v != null) v.increaseStock(item.getQuantity());
            }
        }
        return OrderMapper.view(orders.saveAndFlush(order), true);
    }
}
