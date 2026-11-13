package com.velora.shared.domain;

/** Base for expected business errors. Messages are safe to show to clients. */
public abstract class DomainException extends RuntimeException {
    protected DomainException(String message) {
        super(message);
    }

    public abstract int status();

    public abstract String title();
}
