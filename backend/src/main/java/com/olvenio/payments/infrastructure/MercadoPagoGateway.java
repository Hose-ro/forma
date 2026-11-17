package com.olvenio.payments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.olvenio.payments.application.port.CheckoutSession;
import com.olvenio.payments.application.port.PaymentGateway;
import com.olvenio.payments.application.port.ProviderCharge;
import com.olvenio.payments.domain.Payment;
import com.olvenio.payments.domain.PaymentProvider;
import com.olvenio.payments.domain.PaymentStatus;
import com.olvenio.shared.domain.ConflictException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Mercado Pago Checkout Pro: one preference per payment, possibly several payment resources (card retries). */
@Component
public class MercadoPagoGateway implements PaymentGateway {
    private final MercadoPagoClient client;
    private final PaymentProperties config;

    public MercadoPagoGateway(MercadoPagoClient client, PaymentProperties config) {
        this.client = client;
        this.config = config;
    }

    public PaymentProvider provider() { return PaymentProvider.MERCADO_PAGO; }

    public boolean enabled() { return config.accessToken() != null && !config.accessToken().isBlank(); }

    public void requireConfigured() { config.requireConfigured(); }

    public void requireReady() { client.requireTestSeller(); }

    public CheckoutSession create(Payment p, Instant expiresAt) {
        JsonNode result = client.createPreference(p, expiresAt);
        if (result.path("id").asText().isBlank() || !config.collectorId().equals(result.path("collector_id").asText()))
            throw new MercadoPagoClient.ProviderException();
        return session(result);
    }

    public Optional<CheckoutSession> findCreated(Payment p) {
        JsonNode found = client.preferencesByReference(p.id()).path("elements");
        if (!found.isArray()) throw new MercadoPagoClient.ProviderException();
        if (found.isEmpty()) return Optional.empty();
        return Optional.of(session(official(p, found.get(0).path("id").asText())));
    }

    public CheckoutSession refresh(Payment p) { return session(official(p, p.providerPreferenceId())); }

    public boolean allowedCheckoutUrl(String url) { return MercadoPagoClient.allowedCheckoutUrl(url); }

    public void close(Payment p) {
        config.requireConfigured();
        client.requireTestSeller();
        List<String> preferences = new ArrayList<>();
        if (p.providerPreferenceId() != null) {
            preferences.add(p.providerPreferenceId());
        } else {
            // The creation result was never known: a preference may or may not exist at the provider.
            if (p.createdAt().isAfter(Instant.now().minusSeconds(300)))
                throw new ConflictException("Payment initialization is still in progress; retry in a few minutes");
            JsonNode found = client.preferencesByReference(p.id()).path("elements");
            if (!found.isArray()) throw new MercadoPagoClient.ProviderException();
            found.forEach(e -> preferences.add(e.path("id").asText()));
        }
        for (String id : preferences) client.expirePreference(id);
    }

    public void forEachCharge(Payment p, Instant deadline, Consumer<ProviderCharge> sink) {
        // Search recovers lost webhooks, unknown preference responses, and additional card retries.
        for (int offset = 0; offset < 200; offset += 50) {
            requireBefore(deadline);
            JsonNode results = client.search(p.id(), offset).path("results");
            if (!results.isArray()) throw new MercadoPagoClient.ProviderException();
            for (JsonNode resource : results) {
                // Search results only identify candidates; GET is the authoritative full snapshot.
                requireBefore(deadline);
                sink.accept(charge(client.payment(resource.path("id").asText())));
            }
            if (results.size() < 50) break;
        }
    }

    /** A webhook only authenticates the resource id; the snapshot is always read back from the API. */
    public ProviderCharge webhookCharge(String dataId) {
        JsonNode resource = client.payment(dataId);
        if (!dataId.equals(resource.path("id").asText())) throw new MercadoPagoClient.ProviderException();
        return charge(resource);
    }

    /** Network account checks happen here, before verification takes any row lock. */
    ProviderCharge charge(JsonNode resource) {
        boolean testAccounts = client.testAccountsVerified(resource);
        return toCharge(resource, config.collectorId(), config.production(), testAccounts);
    }

    private static void requireBefore(Instant deadline) {
        if (deadline != null && Instant.now().isAfter(deadline)) throw new MercadoPagoClient.ProviderException();
    }

    /** Authoritative read of a preference, checked against the local payment record. */
    private JsonNode official(Payment p, String preferenceId) {
        JsonNode official = client.preference(preferenceId);
        if (!config.collectorId().equals(official.path("collector_id").asText())
                || !p.id().toString().equals(official.path("external_reference").asText())
                || !preferenceId.equals(official.path("id").asText())) throw new MercadoPagoClient.ProviderException();
        return official;
    }

    private CheckoutSession session(JsonNode preference) {
        return new CheckoutSession(preference.path("id").asText(), client.checkoutUrl(preference));
    }

    public static ProviderCharge toCharge(JsonNode r, String collector, boolean production, boolean testAccounts) {
        UUID reference;
        try { reference = UUID.fromString(r.path("external_reference").asText()); }
        catch (RuntimeException e) { reference = null; } // Resource belongs to another integration.
        String id = r.path("id").asText();
        String status = r.path("status").asText();
        BigDecimal amount = r.path("transaction_amount").isNumber() ? r.path("transaction_amount").decimalValue() : null;
        BigDecimal refunded = r.path("transaction_amount_refunded").isNumber() ? r.path("transaction_amount_refunded").decimalValue() : null;
        Instant updated;
        try { updated = OffsetDateTime.parse(r.path("date_last_updated").asText()).toInstant(); }
        catch (RuntimeException e) { updated = null; }
        boolean live = r.path("live_mode").asBoolean(false);
        // Test credentials may process on the public environment (live_mode=true) for verified test users only.
        // Production accepts only real (live_mode) payments collected by the verified seller.
        boolean account = collector != null && collector.equals(r.path("collector_id").asText()) && r.path("live_mode").isBoolean()
                && (production ? live && testAccounts : !live || testAccounts);
        PaymentStatus next = amount == null || refunded == null ? PaymentStatus.UNKNOWN : status(status, refunded, amount);
        return new ProviderCharge(PaymentProvider.MERCADO_PAGO, reference, id.matches("[0-9]{1,100}") ? id : null, status,
                next, amount, r.path("currency_id").asText(), refunded, updated, account, live, testAccounts);
    }

    public static PaymentStatus status(String status, BigDecimal refunded, BigDecimal amount) {
        return switch (status) {
            case "approved" -> refunded.signum() > 0 ? (refunded.compareTo(amount) >= 0 ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED) : PaymentStatus.APPROVED;
            case "pending", "authorized" -> PaymentStatus.PENDING;
            case "in_process" -> PaymentStatus.IN_PROCESS;
            case "rejected" -> PaymentStatus.REJECTED;
            case "cancelled" -> PaymentStatus.CANCELLED;
            case "refunded" -> PaymentStatus.REFUNDED;
            // Disputes and chargebacks must stop fulfillment and go to review, never masquerade as refunds.
            case "in_mediation", "charged_back" -> PaymentStatus.UNKNOWN;
            default -> PaymentStatus.UNKNOWN;
        };
    }
}
