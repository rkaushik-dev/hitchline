package io.github.rkaushikdev.hitchline.infra.store;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** What a journal line does to its record: write it, or remove it. */
public enum Operation implements WireValue {
    PUT("put"),
    DELETE("delete");

    private final String wire;

    Operation(String wire) {
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

    public static Optional<Operation> fromWire(String wire) {
        return WireValue.fromWire(Operation.class, wire);
    }
}
