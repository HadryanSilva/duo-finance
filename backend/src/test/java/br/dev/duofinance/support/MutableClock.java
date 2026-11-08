package br.dev.duofinance.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Test clock that always holds a microsecond-precision instant. Postgres timestamptz stores microseconds and ROUNDS
 * (not truncates) extra nanoseconds, so a finer instant would round-trip through the database to a different value.
 */
public class MutableClock extends Clock {

    private volatile Instant instant;

    public MutableClock() {
        this(Instant.now());
    }

    public MutableClock(Instant instant) {
        this.instant = instant.truncatedTo(ChronoUnit.MICROS);
    }

    /** Sets the clock to a fresh microsecond-truncated "now". */
    public void reset() {
        this.instant = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public void setInstant(Instant instant) {
        this.instant = instant.truncatedTo(ChronoUnit.MICROS);
    }

    public void advance(Duration duration) {
        this.instant = this.instant.plus(duration).truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
