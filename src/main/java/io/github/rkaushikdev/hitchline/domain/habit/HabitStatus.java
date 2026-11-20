package io.github.rkaushikdev.hitchline.domain.habit;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** Whether a habit is asking for anything: only an active one is. An archived habit stays archived. */
public enum HabitStatus implements WireValue {
    ACTIVE("active"),
    PAUSED("paused"),
    ARCHIVED("archived");

    private final String wire;

    HabitStatus(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }

    @Override
    public String toString() {
        return wire;
    }

    public static Optional<HabitStatus> fromWire(String wire) {
        return WireValue.fromWire(HabitStatus.class, wire);
    }
}
