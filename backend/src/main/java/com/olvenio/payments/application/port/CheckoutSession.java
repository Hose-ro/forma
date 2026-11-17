package com.olvenio.payments.application.port;

/** A checkout opened at a provider: its id there and the hosted page the buyer is sent to. */
public record CheckoutSession(String id, String url) {}
