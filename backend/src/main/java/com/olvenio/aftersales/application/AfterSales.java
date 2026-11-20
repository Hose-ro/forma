package com.olvenio.aftersales.application;

import com.olvenio.orders.domain.Order;
import com.olvenio.orders.domain.OrderStatus;
import com.olvenio.orders.infrastructure.OrderRepository;
import com.olvenio.shared.domain.ConflictException;
import com.olvenio.shared.domain.NotFoundException;
import com.olvenio.shared.security.CurrentUser;
import com.olvenio.shared.security.ResourceAccess;
import com.olvenio.shared.security.SecretTokens;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AfterSales {
    private final JdbcTemplate db;
    private final OrderRepository orders;
    private final SecretTokens tokens;
    private final OrderTracking tracking;
    private final int returnDays;

    public AfterSales(JdbcTemplate db, OrderRepository orders, SecretTokens tokens, OrderTracking tracking,
                      @Value("${app.after-sales.return-days:30}") int returnDays) {
        if (returnDays < 1 || returnDays > 365) throw new IllegalStateException("APP_RETURN_DAYS must be between 1 and 365");
        this.db = db;
        this.orders = orders;
        this.tokens = tokens;
        this.tracking = tracking;
        this.returnDays = returnDays;
    }

    public enum ReturnKind { RETURN, EXCHANGE }
    public enum ReturnStatus { REQUESTED, APPROVED, REJECTED, COMPLETED }
    public record ReturnRequest(@NotNull ReturnKind kind, @NotBlank @Size(max = 500) String items,
                                @NotBlank @Size(max = 500) String reason) {}
    public record ReturnDecision(@NotNull ReturnStatus status, @NotBlank @Size(max = 500) String response) {}
    public record ReturnView(ReturnKind kind, String items, String reason, ReturnStatus status, String response,
                             Instant createdAt, Instant updatedAt) {}
    public record InvoiceRequest(@NotBlank @Pattern(regexp = "[A-ZÑ&]{3,4}[0-9]{6}[A-Z0-9]{3}") String taxId,
                                 @NotBlank @Size(max = 254) String legalName,
                                 @NotBlank @Pattern(regexp = "[0-9]{5}") String postalCode,
                                 @NotBlank @Pattern(regexp = "[0-9]{3}") String taxRegime,
                                 @NotBlank @Pattern(regexp = "[A-Z][0-9]{2}") String cfdiUse) {}
    public record InvoiceUpload(@NotBlank @Size(max = 1398104) String pdf, @NotBlank @Size(max = 1398104) String xml) {}
    public record InvoiceView(String taxId, String legalName, String postalCode, String taxRegime, String cfdiUse,
                              UUID fiscalId, Instant createdAt, Instant issuedAt) {}
    public record View(boolean canReturn, boolean canInvoice, ReturnView returnRequest, InvoiceView invoice, String trackingUrl) {}

    @Transactional(readOnly = true)
    public View customerView(UUID id, String guestToken) {
        Order order = customerOrder(id, guestToken, false);
        boolean signedInOwner = owns(order);
        return new View(canReturn(order), signedInOwner && order.getStatus() == OrderStatus.PAID,
                returnView(id), signedInOwner ? invoiceView(id) : null,
                tracking.carrierUrl(id, order.getCarrier(), order.getTrackingNumber()));
    }

    @Transactional
    public ReturnView requestReturn(UUID id, String guestToken, ReturnRequest request) {
        Order order = customerOrder(id, guestToken, true);
        ReturnView existing = returnView(id);
        if (existing != null) return existing;
        if (!canReturn(order)) throw new ConflictException("El pedido está fuera del plazo o aún no se ha entregado.");
        db.update("insert into order_returns(order_id,kind,items,reason) values (?,?,?,?)",
                id, request.kind().name(), request.items().trim(), request.reason().trim());
        return returnView(id);
    }

    private boolean canReturn(Order order) {
        if (order.getStatus() != OrderStatus.PAID || order.getDeliveredAt() == null) return false;
        var zone = ZoneId.of("America/Mexico_City");
        var lastDay = order.getDeliveredAt().atZone(zone).toLocalDate().plusDays(returnDays);
        return !Instant.now().atZone(zone).toLocalDate().isAfter(lastDay);
    }

    @Transactional
    public InvoiceView requestInvoice(UUID id, InvoiceRequest request) {
        Order order = customerOrder(id, null, true);
        if (!owns(order)) throw missing();
        InvoiceView previous = invoiceView(id);
        if (previous != null) return previous;
        if (order.getStatus() != OrderStatus.PAID) throw new ConflictException("La factura se solicita con el pedido pagado.");
        db.update("insert into order_invoices(order_id,tax_id,legal_name,postal_code,tax_regime,cfdi_use) values (?,?,?,?,?,?)",
                id, request.taxId(), request.legalName().trim(), request.postalCode(), request.taxRegime(), request.cfdiUse());
        return invoiceView(id);
    }

    @Transactional(readOnly = true)
    public View adminView(UUID id) {
        if (!orders.existsById(id)) throw missing();
        return new View(false, false, returnView(id), invoiceView(id), null);
    }

    @Transactional
    public ReturnView reviewReturn(UUID id, ReturnDecision decision) {
        var row = db.query("select status, response from order_returns where order_id=? for update",
                (rs, n) -> new String[]{ rs.getString(1), rs.getString(2) }, id).stream().findFirst().orElseThrow(AfterSales::missing);
        ReturnStatus status = ReturnStatus.valueOf(row[0]);
        ReturnStatus next = decision.status();
        if (status == next && decision.response().trim().equals(row[1])) return returnView(id);
        boolean allowed = (status == ReturnStatus.REQUESTED && List.of(ReturnStatus.APPROVED, ReturnStatus.REJECTED).contains(next))
                || (status == ReturnStatus.APPROVED && next == ReturnStatus.COMPLETED);
        if (!allowed) throw new ConflictException("La solicitud ya cambió de estado. Actualiza la página.");
        db.update("update order_returns set status=?,response=?,updated_at=now(),reviewed_by=? where order_id=?",
                next.name(), decision.response().trim(), CurrentUser.id().orElseThrow(), id);
        return returnView(id);
    }

    @Transactional
    public InvoiceView uploadInvoice(UUID id, InvoiceUpload upload) {
        Order order = orders.lockById(id).orElseThrow(AfterSales::missing);
        InvoiceView invoice = invoiceView(id);
        if (invoice == null) throw missing();
        if (invoice.issuedAt() != null) return invoice;
        CfdiDocument doc = CfdiDocument.read(upload, invoice, order.getCurrency(), order.getTotal());
        db.update("update order_invoices set fiscal_id=?,pdf=?,xml=?,issued_at=now(),issued_by=? where order_id=?",
                doc.fiscalId(), doc.pdf(), doc.xml(), CurrentUser.id().orElseThrow(), id);
        return invoiceView(id);
    }

    public enum InvoiceFormat { pdf, xml }

    @Transactional(readOnly = true)
    public byte[] download(UUID id, InvoiceFormat format) {
        Order order = customerOrder(id, null, false);
        if (!owns(order)) throw missing();
        String sql = format == InvoiceFormat.pdf ? "select pdf" : "select xml";
        return db.query(sql + " from order_invoices where order_id=? and issued_at is not null",
                (rs, n) -> rs.getBytes(1), id).stream().findFirst().orElseThrow(AfterSales::missing);
    }

    private Order customerOrder(UUID id, String token, boolean lock) {
        Order order = (lock ? orders.lockById(id) : orders.findById(id)).orElseThrow(AfterSales::missing);
        if (order.getStatus() == OrderStatus.DRAFT
                || !ResourceAccess.allowed(order.getUserId(), order.getAccessTokenHash(), CurrentUser.id(), token, tokens))
            throw missing();
        return order;
    }

    private static boolean owns(Order order) { return CurrentUser.id().filter(id -> id.equals(order.getUserId())).isPresent(); }
    private static NotFoundException missing() { return new NotFoundException("No encontramos la solicitud o el pedido."); }

    private ReturnView returnView(UUID id) {
        return db.query("select kind,items,reason,status,response,created_at,updated_at from order_returns where order_id=?",
                (rs, n) -> new ReturnView(ReturnKind.valueOf(rs.getString("kind")), rs.getString("items"), rs.getString("reason"),
                        ReturnStatus.valueOf(rs.getString("status")), rs.getString("response"), instant(rs, "created_at"), instant(rs, "updated_at")),
                id).stream().findFirst().orElse(null);
    }

    private InvoiceView invoiceView(UUID id) {
        return db.query("select tax_id,legal_name,postal_code,tax_regime,cfdi_use,fiscal_id,created_at,issued_at from order_invoices where order_id=?",
                (rs, n) -> new InvoiceView(rs.getString("tax_id"), rs.getString("legal_name"), rs.getString("postal_code"),
                        rs.getString("tax_regime"), rs.getString("cfdi_use"), rs.getObject("fiscal_id", UUID.class),
                        instant(rs, "created_at"), instant(rs, "issued_at")), id).stream().findFirst().orElse(null);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
