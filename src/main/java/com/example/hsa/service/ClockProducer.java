package com.example.hsa.service;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.time.Clock;

/** The app's notion of "now". Tests replace it (see TestClock) to check date rules like card expiry. */
public class ClockProducer {

    @Produces
    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }
}
