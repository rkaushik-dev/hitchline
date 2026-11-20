package io.github.rkaushikdev.hitchline.domain.habit;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** Whether a target is a floor to reach or a ceiling to stay under. */
public enum TargetComparison implements WireValue {
    AT_LEAST("at_least"),
    AT_MOST("at_most");

    private final String wire;

    TargetComparison(String wire) {
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

    public static Optional<TargetComparison> fromWire(String wire) {
        return WireValue.fromWire(TargetComparison.class, wire);
    }
}
