package com.velora.cart.infrastructure;

import com.velora.cart.domain.Cart;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface CartRepository extends JpaRepository<Cart, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.id = :id")
    Optional<Cart> lockById(UUID id);

    Optional<Cart> findByUserId(UUID userId);

    Optional<Cart> findFirstByTokenHashAndUserIdIsNullOrderByCreatedAtDesc(String tokenHash);
}
