package io.github.rkaushikdev.hitchline.domain.crew;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** How often somebody wants a digest of their adherence: never, at the end of each hitch, or weekly. */
public enum DigestFrequency implements WireValue {
    NONE("none"),
    HITCH("hitch"),
    WEEK("week");

    private final String wire;

    DigestFrequency(String wire) {
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

    public static Optional<DigestFrequency> fromWire(String wire) {
        return WireValue.fromWire(DigestFrequency.class, wire);
    }
}
