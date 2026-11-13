package com.velora.shared.domain;

public class BusinessRuleException extends DomainException {
    public BusinessRuleException(String message) {
        super(message);
    }

    @Override
    public int status() {
        return 422;
    }

    @Override
    public String title() {
        return "Business rule violated";
    }
}
