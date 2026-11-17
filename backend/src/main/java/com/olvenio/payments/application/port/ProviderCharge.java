package com.olvenio.payments.application.port;

import com.olvenio.payments.domain.PaymentProvider;
import com.olvenio.payments.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One authoritative charge snapshot read from a provider, normalized. Gateways build it only from a
 * provider read (never from a webhook body). Null fields mean the provider sent something unusable;
 * verification then routes the payment to review.
 *
 * @param reference       our payment id, as echoed by the provider; null when absent or foreign
 * @param chargeId        provider charge id, null when malformed
 * @param accountVerified the charge was collected by our account, in the environment we run in
 */
public record ProviderCharge(PaymentProvider provider, UUID reference, String chargeId, String providerStatus,
        PaymentStatus status, BigDecimal amount, String currency, BigDecimal refunded, Instant updatedAt,
        boolean accountVerified, boolean liveMode, boolean testAccountsVerified) {}
