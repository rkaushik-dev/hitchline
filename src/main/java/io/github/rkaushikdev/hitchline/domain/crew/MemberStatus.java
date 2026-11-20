package io.github.rkaushikdev.hitchline.domain.crew;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** Where a member sits with the operator. Only an active member owes anything. */
public enum MemberStatus implements WireValue {
    ACTIVE("active"),
    SUSPENDED("suspended"),
    DEPARTED("departed");

    private final String wire;

    MemberStatus(String wire) {
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

    public static Optional<MemberStatus> fromWire(String wire) {
        return WireValue.fromWire(MemberStatus.class, wire);
    }
}
