package com.olvenio.catalog.api;

import com.olvenio.catalog.application.CatalogDtos.ProductSummaryView;
import com.olvenio.catalog.application.FavoritesService;
import com.olvenio.marketing.MarketingTracking;
import com.olvenio.shared.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/favorites")
public class FavoritesController {
    private final FavoritesService favorites;

    private final MarketingTracking marketing;

    public FavoritesController(FavoritesService favorites, MarketingTracking marketing) {
        this.favorites = favorites;
        this.marketing = marketing;
    }

    @GetMapping
    public List<ProductSummaryView> list(@AuthenticationPrincipal AuthUser user) {
        return favorites.list(user.id());
    }

    @PutMapping("/{productId}")
    public ProductSummaryView add(@AuthenticationPrincipal AuthUser user, @PathVariable UUID productId,
                                  HttpServletRequest request, HttpServletResponse response) {
        var addition = favorites.add(user.id(), productId);
        if (addition.added()) marketing.wishlistAdded(request, response, addition.product());
        return addition.product();
    }

    @DeleteMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal AuthUser user, @PathVariable UUID productId) {
        favorites.remove(user.id(), productId);
    }
}
