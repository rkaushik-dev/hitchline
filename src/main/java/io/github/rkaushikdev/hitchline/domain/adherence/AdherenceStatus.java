package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** How an obligation turned out. Pending is kept apart from missed because a hitch that is still running has not been failed yet, and a report that says otherwise is the fastest way to lose a crew's trust in the tool. */
public enum AdherenceStatus implements WireValue {
    MET("met"),
    PARTIAL("partial"),
    MISSED("missed"),
    PENDING("pending");

    private final String wire;

    AdherenceStatus(String wire) {
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

    public static Optional<AdherenceStatus> fromWire(String wire) {
        return WireValue.fromWire(AdherenceStatus.class, wire);
    }
}
