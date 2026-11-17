package com.olvenio.payments.domain;

import com.olvenio.shared.domain.DomainException;

/** A payment processor could not be reached or answered something that cannot be trusted. Always retryable. */
public class ProviderUnavailableException extends DomainException {
    public ProviderUnavailableException() { this("The payment provider is temporarily unavailable; payment verification is pending"); }
    protected ProviderUnavailableException(String message) { super(message); }
    public int status() { return 503; }
    public String title() { return "Payment provider unavailable"; }
}
