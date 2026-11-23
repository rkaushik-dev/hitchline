package io.github.rkaushikdev.hitchline.domain.habit;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** The six shapes of cadence, by the word stored for each. */
public enum CadenceKind implements WireValue {
    PER_DAY("per_day"),
    PER_ROLE_DAY("per_role_day"),
    PER_HITCH("per_hitch"),
    PER_WEEK("per_week"),
    PER_BLOCK("per_block"),
    PER_WINDOW("per_window");

    private final String wire;

    CadenceKind(String wire) {
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

    public static Optional<CadenceKind> fromWire(String wire) {
        return WireValue.fromWire(CadenceKind.class, wire);
    }
}
