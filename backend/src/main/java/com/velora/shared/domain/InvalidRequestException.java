package com.velora.shared.domain;

public class InvalidRequestException extends DomainException {
    public InvalidRequestException(String message) {
        super(message);
    }

    @Override
    public int status() {
        return 400;
    }

    @Override
    public String title() {
        return "Invalid request";
    }
}
