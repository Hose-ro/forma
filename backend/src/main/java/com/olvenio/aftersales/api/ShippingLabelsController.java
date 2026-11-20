package com.olvenio.aftersales.api;

import com.olvenio.aftersales.application.ShippingLabels;
import com.olvenio.aftersales.infrastructure.EnviaClient.Rate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/orders/{id}/shipping-label")
public class ShippingLabelsController {
    private final ShippingLabels labels;

    public ShippingLabelsController(ShippingLabels labels) { this.labels = labels; }

    public record Review(@NotBlank @Size(max = 500) String reason) {}

    @GetMapping
    public ShippingLabels.View view(@PathVariable UUID id) { return labels.view(id); }

    @PostMapping("/quotes")
    public List<Rate> quotes(@PathVariable UUID id, @Valid @RequestBody ShippingLabels.ParcelInput input) {
        return labels.quotes(id, input);
    }

    @PostMapping
    public ShippingLabels.LabelView purchase(@PathVariable UUID id, @Valid @RequestBody ShippingLabels.Purchase request) {
        return labels.purchase(id, request);
    }

    @PostMapping("/recover")
    public ShippingLabels.LabelView recover(@PathVariable UUID id, @Valid @RequestBody ShippingLabels.Recovery recovery) {
        return labels.recover(id, recovery);
    }

    @PostMapping("/clear")
    public ShippingLabels.LabelView clear(@PathVariable UUID id, @Valid @RequestBody Review review) {
        return labels.clearUncertain(id, review.reason());
    }
}
