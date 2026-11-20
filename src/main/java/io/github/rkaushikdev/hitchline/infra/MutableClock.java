package io.github.rkaushikdev.hitchline.infra;

import io.github.rkaushikdev.hitchline.app.Clock;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** A clock that reads the same instant until it is set or moved on. */
public final class MutableClock implements Clock {

    private final AtomicReference<Instant> current;

    MutableClock(Instant start) {
        this.current = new AtomicReference<>(start);
    }

    @Override
    public Instant now() {
        return current.get();
    }

    public void set(Instant at) {
        current.set(at);
    }

    /** Moves the clock on by {@code milliseconds}, which may be negative but must be finite. */
    public void advance(double milliseconds) {
        if (Double.isNaN(milliseconds) || Double.isInfinite(milliseconds)) {
            throw ValidationException.field("milliseconds", "must be a finite number");
        }
        current.updateAndGet(at -> Zones.instant(at.toEpochMilli() + milliseconds));
    }
}
