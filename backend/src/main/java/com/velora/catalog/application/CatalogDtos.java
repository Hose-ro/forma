package com.velora.catalog.application;

import com.velora.catalog.domain.ProductStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CatalogDtos {
    private CatalogDtos() {
    }

    public static final String SLUG = "^[a-z0-9]+(-[a-z0-9]+)*$";
    public static final String URL = "^(https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?(?:/[^\\s<>\"'\\\\]*)?|/(?!/)[^\\s<>\"'\\\\]*)$";
    public static final String SKU = "^[A-Za-z0-9_-]{2,64}$";
    public static final String COLOR = "^#[0-9a-fA-F]{6}$";

    // ---- Public views ----
    public record CategoryRef(String slug, String name) {
    }

    public record CategoryView(UUID id, String slug, String name, String description, String imageUrl,
                               int sortOrder) {
    }

    public record ImageView(String url, String altText) {
    }

    public record VariantView(UUID id, String sku, String name, String color, BigDecimal price,
                              BigDecimal compareAtPrice, String currency, int stock, boolean available,
                              String imageUrl) {
    }

    public record ProductSummaryView(UUID id, String slug, String name, CategoryRef category, BigDecimal price,
                                     BigDecimal compareAtPrice, String currency, boolean featured,
                                     boolean available, String imageUrl, UUID defaultVariantId, Instant createdAt) {
    }

    public record ProductDetailView(UUID id, String slug, String name, String description, String material,
                                    CategoryRef category, BigDecimal price, BigDecimal compareAtPrice,
                                    String currency, boolean featured, boolean available, List<ImageView> images,
                                    List<VariantView> variants, Instant createdAt) {
    }

    public record ProductFilter(String category, BigDecimal minPrice, BigDecimal maxPrice, Boolean available,
                                String q) {
    }

    // ---- Admin views ----
    public record AdminVariantView(UUID id, String sku, String name, String color, BigDecimal price,
                                   BigDecimal compareAtPrice, String currency, int stock, boolean active,
                                   int position, String imageUrl) {
    }

    public record AdminProductView(UUID id, String slug, String name, String description, String material,
                                   UUID categoryId, String categorySlug, ProductStatus status, boolean featured,
                                   boolean available, BigDecimal price, BigDecimal compareAtPrice, String currency,
                                   List<ImageView> images, List<AdminVariantView> variants, Instant createdAt,
                                   Instant updatedAt) {
    }

    public enum DeleteOutcome { DELETED, ARCHIVED }

    public record DeleteResult(UUID id, DeleteOutcome outcome) {
    }

    // ---- Admin commands ----
    public record VariantInput(
            UUID id,
            @NotBlank @Pattern(regexp = SKU) String sku,
            @NotBlank @Size(max = 120) String name,
            @Pattern(regexp = COLOR) String color,
            @NotNull @DecimalMin("0.00") @Digits(integer = 15, fraction = 2) BigDecimal price,
            @DecimalMin("0.00") @Digits(integer = 15, fraction = 2) BigDecimal compareAtPrice,
            @NotNull @Min(0) @Max(1_000_000) Integer stock,
            Boolean active,
            @Min(0) Integer position,
            @Size(max = 500) @Pattern(regexp = URL) String imageUrl) {
    }

    public record ImageInput(@NotBlank @Size(max = 500) @Pattern(regexp = URL) String url,
                             @Size(max = 200) String altText) {
    }

    public record ProductCreateRequest(
            @NotNull UUID categoryId,
            @NotBlank @Size(max = 160) @Pattern(regexp = SLUG) String slug,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 5000) String description,
            @Size(max = 500) String material,
            ProductStatus status,
            Boolean featured,
            @NotNull @DecimalMin("0.00") @Digits(integer = 15, fraction = 2) BigDecimal price,
            @DecimalMin("0.00") @Digits(integer = 15, fraction = 2) BigDecimal compareAtPrice,
            @NotEmpty @Size(max = 50) List<@NotNull @Valid VariantInput> variants,
            @Size(max = 20) List<@NotNull @Valid ImageInput> images) {
    }

    /** Partial update: null fields are left unchanged. `variants` (if present) is upserted by id; omitted variants are deactivated. */
    public record ProductPatchRequest(
            UUID categoryId,
            @Size(max = 160) @Pattern(regexp = SLUG) String slug,
            @Size(min = 1, max = 200) String name,
            @Size(max = 5000) String description,
            @Size(max = 500) String material,
            ProductStatus status,
            Boolean featured,
            @DecimalMin("0.00") @Digits(integer = 15, fraction = 2) BigDecimal price,
            @DecimalMin("0.00") @Digits(integer = 15, fraction = 2) BigDecimal compareAtPrice,
            Boolean clearCompareAtPrice,
            @Size(min = 1, max = 50) List<@NotNull @Valid VariantInput> variants,
            @Size(max = 20) List<@NotNull @Valid ImageInput> images) {
    }

    public record CategoryCreateRequest(
            @NotBlank @Size(max = 120) @Pattern(regexp = SLUG) String slug,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @Size(max = 500) @Pattern(regexp = URL) String imageUrl,
            Integer sortOrder) {
    }

    public record CategoryPatchRequest(
            @Size(max = 120) @Pattern(regexp = SLUG) String slug,
            @Size(min = 1, max = 120) String name,
            @Size(max = 500) String description,
            @Size(max = 500) @Pattern(regexp = URL) String imageUrl,
            Integer sortOrder) {
    }
}
