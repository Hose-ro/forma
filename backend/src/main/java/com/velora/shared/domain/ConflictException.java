package com.velora.shared.domain;

public class ConflictException extends DomainException {
    public ConflictException(String message) {
        super(message);
    }

    @Override
    public int status() {
        return 409;
    }

    @Override
    public String title() {
        return "Conflict";
    }
}
