package com.example.scheduler.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the system clock with a {@link MutableClock} for deterministic tests. */
@TestConfiguration
public class TestClockConfig {

    public static final String T0 = "2026-09-13T00:00:00Z";

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return MutableClock.utc(T0);
    }
}
