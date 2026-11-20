package io.github.rkaushikdev.hitchline.domain.schedule;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** What a cadence was measured over. A day scoped obligation is owed on one date; a hitch scoped one is owed across the whole rotation and can be satisfied on any day of it. */
public enum ObligationScope implements WireValue {
    DAY("day"),
    BLOCK("block"),
    HITCH("hitch"),
    WINDOW("window");

    private final String wire;

    ObligationScope(String wire) {
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

    public static Optional<ObligationScope> fromWire(String wire) {
        return WireValue.fromWire(ObligationScope.class, wire);
    }
}
