package com.jtalk.userservice.config;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

import java.time.Clock;

/** Single injectable time source so services stay deterministic in tests. */
@Factory
class ClockFactory {

    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }
}
