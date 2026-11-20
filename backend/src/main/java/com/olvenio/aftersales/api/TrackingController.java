package com.olvenio.aftersales.api;

import com.olvenio.aftersales.application.OrderTracking;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class TrackingController {
    private final OrderTracking tracking;

    public TrackingController(OrderTracking tracking) { this.tracking = tracking; }

    public record TrackingRequest(@NotBlank @Size(max = 80) String token) {}

    @PostMapping("/api/v1/orders/{id}/tracking")
    public OrderTracking.TrackingView tracking(@PathVariable UUID id, @Valid @RequestBody TrackingRequest request) {
        return tracking.read(id, request.token());
    }
}
