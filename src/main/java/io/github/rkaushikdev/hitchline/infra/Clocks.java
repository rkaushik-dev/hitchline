package io.github.rkaushikdev.hitchline.infra;

import io.github.rkaushikdev.hitchline.app.Clock;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Clocks. Nothing in the code base reads the system time directly, so every reading of the
 * current time comes through one of these and a test can pin it.
 */
public final class Clocks {

    private Clocks() {}

    /** The system time, to the millisecond. */
    public static Clock systemClock() {
        return () -> Instant.ofEpochMilli(System.currentTimeMillis());
    }

    public static MutableClock fixedClock(Instant at) {
        return new MutableClock(at);
    }

    /** A fixed clock at an ISO 8601 timestamp. */
    public static MutableClock fixedClock(String at) {
        return new MutableClock(Zones.parseInstant(at));
    }

    /** A clock that moves on by a second every time it is read. */
    public static Clock tickingClock(String start) {
        return tickingClock(Zones.parseInstant(start), 1_000);
    }

    public static Clock tickingClock(String start, long stepMs) {
        return tickingClock(Zones.parseInstant(start), stepMs);
    }

    /**
     * A clock that moves on by a fixed step every time it is read. Handy where a sequence of events
     * needs distinct timestamps without a test setting each one.
     */
    public static Clock tickingClock(Instant start, long stepMs) {
        if (stepMs < 1) {
            throw ValidationException.field("stepMs", "must be a whole number above zero, got " + stepMs);
        }
        AtomicLong next = new AtomicLong(start.toEpochMilli());
        return () -> Instant.ofEpochMilli(next.getAndAdd(stepMs));
    }

    /** Reads a clock and rounds down to the second, which is what the api reports. */
    public static Instant nowToSecond(Clock clock) {
        return Instant.ofEpochMilli(Math.floorDiv(clock.now().toEpochMilli(), 1000) * 1000);
    }
}
