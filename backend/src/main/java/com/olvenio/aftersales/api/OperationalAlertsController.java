package com.olvenio.aftersales.api;

import com.olvenio.aftersales.application.OperationalAlerts;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationalAlertsController {
    private final OperationalAlerts alerts;

    public OperationalAlertsController(OperationalAlerts alerts) { this.alerts = alerts; }

    @GetMapping("/api/v1/admin/alerts")
    public List<OperationalAlerts.Alert> active() { return alerts.active(); }

    @PostMapping("/api/v1/admin/orders/{id}/email-retry/{kind}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retry(@PathVariable UUID id, @PathVariable OperationalAlerts.OrderEmail kind) {
        alerts.retryOrderEmail(id, kind);
    }
}
