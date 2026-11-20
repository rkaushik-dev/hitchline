package io.github.rkaushikdev.hitchline.config;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** Where records are kept: in memory for a demo or a test, or in the journal file. */
public enum StoreKind implements WireValue {
    MEMORY("memory"),
    FILE("file");

    private final String wire;

    StoreKind(String wire) {
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

    public static Optional<StoreKind> fromWire(String wire) {
        return WireValue.fromWire(StoreKind.class, wire);
    }
}
