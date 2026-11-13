package com.velora.orders.application;

import com.velora.orders.application.OrderDtos.OrderSummaryView;
import com.velora.orders.application.OrderDtos.OrderView;
import com.velora.orders.domain.Order;
import com.velora.orders.domain.OrderStatus;
import com.velora.orders.infrastructure.OrderRepository;
import com.velora.shared.api.PageRequests;
import com.velora.shared.api.PageResponse;
import com.velora.shared.domain.NotFoundException;
import com.velora.shared.security.CurrentUser;
import com.velora.shared.security.ResourceAccess;
import com.velora.shared.security.SecretTokens;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    private static final Sort NEWEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
    private final OrderRepository orders;
    private final SecretTokens tokens;

    public OrderQueryService(OrderRepository orders, SecretTokens tokens) {
        this.orders = orders;
        this.tokens = tokens;
    }

    /** Owner session, admin, or the order's secret token. Anything else is indistinguishable from "not found". */
    public OrderView get(UUID id, String orderToken) {
        Order order = orders.findById(id).orElseThrow(() -> new NotFoundException("Order not found"));
        boolean allowed = CurrentUser.isAdmin() || ResourceAccess.allowed(order.getUserId(),
                order.getAccessTokenHash(), CurrentUser.id(), orderToken, tokens);
        if (!allowed) throw new NotFoundException("Order not found");
        return OrderMapper.view(order, false);
    }

    public PageResponse<OrderSummaryView> mine(UUID userId, Integer page, Integer size) {
        return PageResponse.of(orders.findByUserIdAndStatusNot(userId, OrderStatus.DRAFT,
                PageRequests.of(page, size, NEWEST)), OrderMapper::summary);
    }

    /** Orders placed by this browser as a guest (identified by the guest token). */
    public PageResponse<OrderSummaryView> guestOrders(String guestToken, Integer page, Integer size) {
        var pageable = PageRequests.of(page, size, NEWEST);
        if (guestToken == null) return PageResponse.of(org.springframework.data.domain.Page.<Order>empty(pageable), OrderMapper::summary);
        return PageResponse.of(orders.findByAccessTokenHashAndUserIdIsNullAndStatusNot(tokens.hash(guestToken),
                OrderStatus.DRAFT, pageable), OrderMapper::summary);
    }

    /** Admin listing; quotes (DRAFT) are excluded unless explicitly requested. */
    public PageResponse<OrderSummaryView> adminList(OrderStatus status, Integer page, Integer size) {
        var pageable = PageRequests.of(page, size, NEWEST);
        var result = status == null ? orders.findByStatusNot(OrderStatus.DRAFT, pageable)
                : orders.findByStatus(status, pageable);
        return PageResponse.of(result, OrderMapper::summary);
    }

    public OrderView adminGet(UUID id) {
        return orders.findById(id).map(o -> OrderMapper.view(o, true))
                .orElseThrow(() -> new NotFoundException("Order not found"));
    }

    public long countRegistered() {
        return orders.countByStatusNot(OrderStatus.DRAFT);
    }

    public long countByStatus(OrderStatus status) {
        return orders.countByStatus(status);
    }
}
