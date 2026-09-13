package com.example.scheduler.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test clock: the injectable time source, controlled by the test. */
public final class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public static MutableClock utc(String isoInstant) {
        return new MutableClock(Instant.parse(isoInstant), ZoneOffset.UTC);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    public void setInstant(String isoInstant) {
        this.instant = Instant.parse(isoInstant);
    }

    public void advance(Duration duration) {
        this.instant = this.instant.plus(duration);
    }
}
