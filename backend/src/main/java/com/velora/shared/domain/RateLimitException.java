package com.velora.shared.domain;

public class RateLimitException extends DomainException {
    public RateLimitException() { super("Too many requests; try again later"); }
    public int status() { return 429; }
    public String title() { return "Too many requests"; }
}
