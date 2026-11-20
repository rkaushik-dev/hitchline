package io.github.rkaushikdev.hitchline.domain.crew;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** The unit somebody reads distances in. Readings are always held in kilometres. */
public enum DistanceUnit implements WireValue {
    KILOMETRES("kilometres"),
    MILES("miles");

    private final String wire;

    DistanceUnit(String wire) {
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

    public static Optional<DistanceUnit> fromWire(String wire) {
        return WireValue.fromWire(DistanceUnit.class, wire);
    }
}
