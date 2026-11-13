package com.velora.catalog.infrastructure;

import com.velora.catalog.domain.ProductVariant;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {
    boolean existsBySkuIgnoreCase(String sku);

    @Query("select v from ProductVariant v join fetch v.product where v.id in :ids")
    List<ProductVariant> findAllWithProduct(Collection<UUID> ids);

    /** Row locks in a stable order (by id) to avoid deadlocks between concurrent checkouts. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProductVariant v where v.id in :ids order by v.id")
    List<ProductVariant> lockAllById(Collection<UUID> ids);
}
