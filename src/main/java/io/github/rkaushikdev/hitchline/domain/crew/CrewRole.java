package io.github.rkaushikdev.hitchline.domain.crew;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** What somebody is in a crew: a plain member, its lead or its coordinator. */
public enum CrewRole implements WireValue {
    MEMBER("member"),
    LEAD("lead"),
    COORDINATOR("coordinator");

    private final String wire;

    CrewRole(String wire) {
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

    public static Optional<CrewRole> fromWire(String wire) {
        return WireValue.fromWire(CrewRole.class, wire);
    }
}
