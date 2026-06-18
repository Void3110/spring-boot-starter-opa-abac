package dev.dmitriikonovalov.opaabac.security.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A hand-advanced {@link Clock} for the resilience tests — virtual time, zero wall-clock dependence
 * (ADR 0017 §Proof). Combined with a no-op {@code sleeper} that {@link #advance(Duration) advances} this
 * clock by the requested backoff, every retry/backoff/breaker test runs instantly and deterministically.
 */
final class MutableClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    MutableClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private MutableClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    static MutableClock startingAtEpoch() {
        return new MutableClock(Instant.EPOCH);
    }

    void advance(Duration by) {
        now = now.plus(by);
    }

    void advanceMillis(long millis) {
        now = now.plusMillis(millis);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public long millis() {
        return now.toEpochMilli();
    }
}
