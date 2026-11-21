package com.olvenio.storefront.api;

import com.olvenio.storefront.application.HomeHeroDtos.HeroView;
import com.olvenio.storefront.application.HomeHeroService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/home")
@Tag(name = "Storefront")
public class HomeController {

    private final HomeHeroService heroes;

    public HomeController(HomeHeroService heroes) {
        this.heroes = heroes;
    }

    @Operation(summary = "Enabled home heroes; the storefront rotates them every 12 hours in variant order")
    @GetMapping("/heroes")
    public List<HeroView> heroes() {
        return heroes.published();
    }
}
