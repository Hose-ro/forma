package com.olvenio.payments.application.port;

import com.olvenio.payments.domain.Payment;
import com.olvenio.payments.domain.PaymentProvider;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A payment processor behind a hosted checkout. The application keeps all state and safety rules
 * (durable CREATING before the network, order locks, closing before releasing stock); a gateway only talks
 * to its provider and normalizes what it reads. Every method may throw
 * {@link com.olvenio.payments.domain.ProviderUnavailableException}.
 */
public interface PaymentGateway {
    PaymentProvider provider();

    /** Credentials are present. Cheap, no network. Disabled gateways are not offered to buyers. */
    boolean enabled();

    /** Local configuration check; throws ConflictException when incomplete. No network. */
    void requireConfigured();

    /** Confirms, possibly over the network, that the configured account matches the environment. */
    void requireReady();

    /** Creates the hosted checkout. Must expire no later than {@code expiresAt}. */
    CheckoutSession create(Payment payment, Instant expiresAt);

    /**
     * Variant for providers that require buyer contact data. Default: the buyer is not shared with the provider.
     * Must send the same request for the same payment, so an idempotent retry replays the original checkout.
     */
    default CheckoutSession create(Payment payment, Instant expiresAt, CheckoutBuyer buyer) { return create(payment, expiresAt); }

    /** After an uncertain creation: the checkout the provider holds for this payment, if any. */
    Optional<CheckoutSession> findCreated(Payment payment);

    /** Re-reads the stored checkout to repair its link; never creates a new one. */
    CheckoutSession refresh(Payment payment);

    boolean allowedCheckoutUrl(String url);

    /** Stops every way of starting a new charge from this payment's checkout. */
    void close(Payment payment);

    /**
     * Finishes a checkout the buyer approved, for providers where the merchant takes the money (PayPal capture).
     * Called with the order locked and the checkout verified open, so a closed checkout is never completed.
     * Must be idempotent. Default: the provider charges by itself, nothing to do.
     */
    default void complete(Payment payment) {}

    /** Reads every charge started for this payment. {@code deadline} may be null. */
    void forEachCharge(Payment payment, Instant deadline, Consumer<ProviderCharge> sink);
}
