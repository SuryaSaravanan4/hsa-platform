package com.example.hsa;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Replaces the production Clock in tests. Behaves like the system UTC clock until a test pins it
 * with {@link #setTo}; tests that pin it must call {@link #reset} in a finally block.
 */
@Alternative
@Priority(1)
@Singleton
public class TestClock extends Clock {

    private volatile Clock delegate = Clock.systemUTC();

    public void setTo(Instant instant) {
        delegate = Clock.fixed(instant, ZoneOffset.UTC);
    }

    public void reset() {
        delegate = Clock.systemUTC();
    }

    @Override
    public ZoneId getZone() {
        return delegate.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return delegate.withZone(zone);
    }

    @Override
    public Instant instant() {
        return delegate.instant();
    }
}
