package com.olvenio.payments.application.port;

/** Contact data of the buyer, sent only to providers whose checkout requires it. Phone may be null. */
public record CheckoutBuyer(String name, String email, String phone) {}
