package com.velora.shared.security;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Single-instance, bounded fixed-window limiter. Never evicts live counters to admit new keys. */
public final class RequestLimits {
    private record Window(long expiresAt, int count) {}
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;
    private final int capacity;
    private long nextCleanup;

    public RequestLimits() { this(Clock.systemUTC(), 50_000); }

    public RequestLimits(Clock clock, int capacity) {
        this.clock = clock;
        this.capacity = capacity;
    }

    /** Returns seconds until retry, or zero when admitted. Keys must be server-generated or hashed. */
    public synchronized long retryAfter(String key, int maximum, Duration duration) {
        long now = clock.millis();
        if (now >= nextCleanup) {
            windows.values().removeIf(w -> w.expiresAt() <= now);
            nextCleanup = now + 60_000;
        }
        Window w = windows.get(key);
        if (w == null || w.expiresAt() <= now) {
            if (w == null && windows.size() >= capacity) return 60;
            windows.put(key, new Window(now + duration.toMillis(), 1));
            return 0;
        }
        if (w.count() >= maximum) return Math.max(1, (w.expiresAt() - now + 999) / 1000);
        windows.put(key, new Window(w.expiresAt(), w.count() + 1));
        return 0;
    }
}
