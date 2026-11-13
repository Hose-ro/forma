package com.velora.checkout.api;

import com.velora.shared.config.AppProperties;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public shipping rules so the storefront shows exactly what the server will charge. */
@RestController
@Tag(name = "Checkout")
public class ShippingController {

    public record Zone(String state, BigDecimal rate) {
    }

    public record ShippingRules(String currency, BigDecimal flat, BigDecimal freeFrom, List<Zone> zones,
                                boolean onlyZones, String deliveryEstimate) {
    }

    private final AppProperties props;

    public ShippingController(AppProperties props) {
        this.props = props;
    }

    @GetMapping("/api/v1/checkout/shipping")
    public ShippingRules rules() {
        var c = props.checkout();
        return new ShippingRules(props.currency(), c.shippingFlat(), c.shippingFreeFrom(),
                c.zoneRates().entrySet().stream().map(e -> new Zone(e.getKey(), e.getValue())).toList(),
                c.shippingOnlyZones(), c.deliveryEstimate());
    }
}
