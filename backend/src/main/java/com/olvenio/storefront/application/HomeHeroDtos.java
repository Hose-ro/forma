package com.olvenio.storefront.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.olvenio.catalog.application.CatalogDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Content of the four home heroes. Images follow the catalog URL rule (https or a site path). */
public final class HomeHeroDtos {

    private HomeHeroDtos() {
    }

    /** An image tile that links to a category. */
    public record Tile(
            @NotBlank @Size(max = 40) String label,
            @NotBlank @Size(max = 80) String title,
            @NotBlank @Size(max = 500) @Pattern(regexp = CatalogDtos.URL) String imageUrl,
            @Size(max = 160) String alt,
            @NotBlank @Size(max = 120) @Pattern(regexp = CatalogDtos.SLUG) String categorySlug) {
    }

    /** A: headline plus one large scene and two smaller tiles. */
    public record HeroA(
            @Size(max = 60) String meta,
            @NotBlank @Size(max = 80) String title,
            @Size(max = 80) String italic,
            @Size(max = 240) String body,
            @NotBlank @Size(max = 40) String ctaLabel,
            @NotNull @Valid Tile main,
            @NotNull @Size(min = 2, max = 2) List<@NotNull @Valid Tile> tiles) {
    }

    /** B: one column per category; a blank title means "Tres mundos." from the category count. */
    public record HeroB(
            @Size(max = 80) String title,
            @Size(max = 80) String italic,
            @Size(max = 240) String body) {
    }

    public record Campaign(
            @NotBlank @Size(max = 40) String world,
            @Size(max = 60) String eyebrow,
            @NotBlank @Size(max = 60) String title,
            @Size(max = 60) String italic,
            @Size(max = 240) String body,
            @NotBlank @Size(max = 160) @Pattern(regexp = CatalogDtos.SLUG) String productSlug) {
    }

    /** C: one featured product per campaign tab. */
    public record HeroC(@NotNull @Size(min = 1, max = 4) List<@NotNull @Valid Campaign> campaigns) {
    }

    public record Moment(
            @NotNull @Pattern(regexp = "manana|tarde|noche") String id,
            @NotBlank @Size(max = 30) String label,
            @NotBlank @Size(max = 80) String line,
            @NotBlank @Size(max = 500) @Pattern(regexp = CatalogDtos.URL) String imageUrl,
            @Size(max = 160) String alt,
            @NotNull @Size(min = 1, max = 3)
            List<@NotBlank @Size(max = 160) @Pattern(regexp = CatalogDtos.SLUG) String> productSlugs) {
    }

    /** D: products grouped by moment of the day; the open tab follows the store clock. */
    public record HeroD(
            @NotBlank @Size(max = 60) String title,
            @Size(max = 60) String italic,
            @NotNull @Size(min = 1, max = 3) List<@NotNull @Valid Moment> moments) {
    }

    public record HeroUpdate<T>(boolean enabled, @NotNull @Valid T content) {
    }

    public record HeroView(String variant, boolean enabled, JsonNode content, Instant updatedAt) {
    }
}
