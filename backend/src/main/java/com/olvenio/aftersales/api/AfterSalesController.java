package com.olvenio.aftersales.api;

import static com.olvenio.shared.config.SecurityConfig.ORDER_TOKEN_HEADER;

import com.olvenio.aftersales.application.AfterSales;
import com.olvenio.shared.security.GuestSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AfterSalesController {
    private final AfterSales afterSales;
    private final GuestSession guests;

    public AfterSalesController(AfterSales afterSales, GuestSession guests) {
        this.afterSales = afterSales;
        this.guests = guests;
    }

    @GetMapping("/api/v1/orders/{id}/after-sales")
    public AfterSales.View view(@PathVariable UUID id, @RequestHeader(value = ORDER_TOKEN_HEADER, required = false) String token,
                                HttpServletRequest request) {
        return afterSales.customerView(id, guests.resolve(token, request));
    }

    @PostMapping("/api/v1/orders/{id}/returns")
    public AfterSales.ReturnView requestReturn(@PathVariable UUID id,
                                               @RequestHeader(value = ORDER_TOKEN_HEADER, required = false) String token,
                                               HttpServletRequest request, @Valid @RequestBody AfterSales.ReturnRequest body) {
        return afterSales.requestReturn(id, guests.resolve(token, request), body);
    }

    @PostMapping("/api/v1/me/orders/{id}/invoice")
    public AfterSales.InvoiceView requestInvoice(@PathVariable UUID id, @Valid @RequestBody AfterSales.InvoiceRequest body) {
        return afterSales.requestInvoice(id, body);
    }

    @GetMapping("/api/v1/me/orders/{id}/invoice/{format}")
    public ResponseEntity<byte[]> download(@PathVariable UUID id, @PathVariable AfterSales.InvoiceFormat format) {
        byte[] bytes = afterSales.download(id, format);
        String filename = "factura-" + id + "." + format.name();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(bytes);
    }

    @GetMapping("/api/v1/admin/orders/{id}/after-sales")
    public AfterSales.View adminView(@PathVariable UUID id) { return afterSales.adminView(id); }

    @PatchMapping("/api/v1/admin/orders/{id}/returns")
    public AfterSales.ReturnView review(@PathVariable UUID id, @Valid @RequestBody AfterSales.ReturnDecision decision) {
        return afterSales.reviewReturn(id, decision);
    }

    @PostMapping("/api/v1/admin/orders/{id}/invoice")
    public AfterSales.InvoiceView upload(@PathVariable UUID id, @Valid @RequestBody AfterSales.InvoiceUpload upload) {
        return afterSales.uploadInvoice(id, upload);
    }
}
