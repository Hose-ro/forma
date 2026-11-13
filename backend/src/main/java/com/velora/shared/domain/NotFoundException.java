package com.velora.shared.domain;

public class NotFoundException extends DomainException {
    public NotFoundException(String message) {
        super(message);
    }

    @Override
    public int status() {
        return 404;
    }

    @Override
    public String title() {
        return "Resource not found";
    }
}
