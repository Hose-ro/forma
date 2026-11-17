package com.olvenio.payments.application;

import com.olvenio.payments.application.port.PaymentGateway;
import com.olvenio.payments.domain.Payment;
import com.olvenio.payments.infrastructure.PaymentRepository;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Closes checkouts at their provider. The local close (checkout_closed_at) is always committed first under the
 * order lock; this network step then stops the provider from starting new charges and re-reads every charge,
 * so a payment made just before the close is still recorded.
 */
@Service
public class CheckoutCloser {
    private static final Logger log = LoggerFactory.getLogger(CheckoutCloser.class);
    private final PaymentRepository payments;
    private final PaymentGateways gateways;
    private final PaymentVerificationService verification;

    public CheckoutCloser(PaymentRepository payments, PaymentGateways gateways, PaymentVerificationService verification) {
        this.payments = payments;
        this.gateways = gateways;
        this.verification = verification;
    }

    /** Network step, outside any transaction. Idempotent: closing twice is harmless. */
    public void closeAtProvider(Payment p) {
        PaymentGateway gateway = gateways.get(p.provider());
        gateway.close(p);
        gateway.forEachCharge(p, null, verification::apply);
        payments.providerClosed(p.id());
        payments.event(p.id(), null, "PREFERENCE_CLOSED", "{}", "PROCESSED");
    }

    /** Finishes closes that failed at the provider, and closes checkouts of orders that were paid elsewhere. */
    @Scheduled(fixedDelayString = "${app.payments.close-sweep-delay:120000}", initialDelay = 60000)
    public void sweep() {
        List<PaymentGateway> enabled = gateways.enabled();
        if (enabled.isEmpty()) return;
        String providers = enabled.stream().map(g -> "'" + g.provider().name() + "'").collect(Collectors.joining(","));
        List<UUID> due = payments.db().queryForList("""
                select id from payments where checkout_closed_at is not null and provider_closed_at is null
                    and checkout_closed_at < now() - interval '1 minute' and provider in (%s)
                    order by checkout_closed_at limit 20""".formatted(providers), UUID.class);
        for (UUID id : due) {
            try {
                payments.find(id).ifPresent(this::closeAtProvider);
            } catch (RuntimeException e) {
                log.warn("Checkout close of payment {} deferred ({})", id, e.getClass().getSimpleName());
            }
        }
    }
}
