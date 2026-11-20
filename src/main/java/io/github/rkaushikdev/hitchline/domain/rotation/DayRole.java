package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.List;
import java.util.Optional;

/**
 * What a given day means for the person working it. Everything downstream keys off these four,
 * because "did they do it today" is a different question on a travel day than on a shift day.
 */
public enum DayRole implements WireValue {
    ONSHIFT("onshift"),
    OFFSHIFT("offshift"),
    TRAVEL("travel"),
    STANDBY("standby");

    /** The four roles, in the order they are listed everywhere. */
    public static final List<DayRole> ALL = List.of(values());

    private final String wire;

    DayRole(String wire) {
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

    public static Optional<DayRole> fromWire(String wire) {
        return WireValue.fromWire(DayRole.class, wire);
    }

    public static boolean isDayRole(String value) {
        return fromWire(value).isPresent();
    }
}
