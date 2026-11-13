package com.velora.orders.infrastructure;

import com.velora.orders.domain.Order;
import com.velora.orders.domain.OrderStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByIdempotencyKey(String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SalesOrder o where o.id = :id")
    Optional<Order> lockById(UUID id);

    Page<Order> findByUserIdAndStatusNot(UUID userId, OrderStatus status, Pageable pageable);

    Page<Order> findByAccessTokenHashAndUserIdIsNullAndStatusNot(String hash, OrderStatus status, Pageable pageable);

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    Page<Order> findByStatusNot(OrderStatus status, Pageable pageable);

    long countByStatus(OrderStatus status);

    long countByStatusNot(OrderStatus status);

    @Query(value = "select nextval('order_number_seq')", nativeQuery = true)
    long nextOrderNumber();
}
