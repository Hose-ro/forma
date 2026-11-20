package com.olvenio.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.olvenio.catalog.application.CatalogAdminService;
import com.olvenio.catalog.application.CatalogDtos.ProductPatchRequest;
import com.olvenio.catalog.application.CatalogDtos.VariantInput;
import com.olvenio.catalog.domain.Category;
import com.olvenio.catalog.domain.Product;
import com.olvenio.catalog.domain.ProductVariant;
import com.olvenio.catalog.infrastructure.CategoryRepository;
import com.olvenio.catalog.infrastructure.ProductRepository;
import com.olvenio.catalog.infrastructure.ProductVariantRepository;
import com.olvenio.shared.config.AppProperties;
import com.olvenio.shared.domain.ConflictException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** A01: an admin form opened before a sale must never write the old stock back. */
class StaleStockTest {
    private final ProductRepository products = mock(ProductRepository.class);
    private final CatalogAdminService service = new CatalogAdminService(products,
            mock(ProductVariantRepository.class), mock(CategoryRepository.class),
            new AppProperties(null, "test-secret-test-secret-test-secret-42", false, "MXN", null, null));
    private final UUID productId = UUID.randomUUID();
    private final UUID variantId = UUID.randomUUID();
    private ProductVariant variant;

    /** The administrator loaded the product with 10 units; one was sold afterwards, leaving 9. */
    private void productWithStockAfterSale() {
        Product p = new Product(new Category("living", "Living", null, null, 0), "vase", "Vase",
                new BigDecimal("100.00"), "MXN");
        variant = p.addVariant();
        ReflectionTestUtils.setField(variant, "id", variantId);
        variant.update("VASE-1", "Original", null, new BigDecimal("100.00"), null, 10, true, 0, null);
        variant.decreaseStock(1);
        when(products.findById(productId)).thenReturn(Optional.of(p));
        when(products.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    private ProductPatchRequest patch(String description, Integer stock, Integer expectedStock) {
        var v = new VariantInput(variantId, "VASE-1", "Original", null, new BigDecimal("100.00"), null, stock,
                expectedStock, null, 0, null);
        return new ProductPatchRequest(null, null, null, description, null, null, null, null, null, null, List.of(v), null, null);
    }

    @Test void descriptionSaveFromStaleFormKeepsSoldUnitsSold() {
        productWithStockAfterSale();
        service.update(productId, patch("New copy", null, null));
        assertThat(variant.getStock()).isEqualTo(9);
    }

    @Test void staleStockIsRejectedInsteadOfRestored() {
        productWithStockAfterSale();
        assertThatThrownBy(() -> service.update(productId, patch(null, 10, 10))).isInstanceOf(ConflictException.class);
        assertThat(variant.getStock()).isEqualTo(9);
    }

    @Test void stockChangeBasedOnCurrentValueIsApplied() {
        productWithStockAfterSale();
        service.update(productId, patch(null, 12, 9));
        assertThat(variant.getStock()).isEqualTo(12);
    }
}
