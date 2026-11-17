package com.olvenio.payments.domain;

/** Payment processors a checkout can be opened with. Names are persisted in payments.provider. */
public enum PaymentProvider {
    MERCADO_PAGO, STRIPE, PAYPAL, CONEKTA;

    public static PaymentProvider parse(String value) {
        try { return valueOf(value); }
        catch (RuntimeException e) { throw new com.olvenio.shared.domain.InvalidRequestException("Unknown payment provider"); }
    }
}
