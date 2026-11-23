package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/**
 * The two ways a roster breaks the duty limits: a trip that keeps somebody away too long, or a
 * break at home too short to recover in before they go back.
 */
public enum DutyRule implements WireValue {
    LONG_TRIP("long_trip"),
    SHORT_BREAK("short_break");

    private final String wire;

    DutyRule(String wire) {
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

    public static Optional<DutyRule> fromWire(String wire) {
        return WireValue.fromWire(DutyRule.class, wire);
    }
}
