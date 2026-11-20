package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.util.WireValue;

/** Where the role of a day came from: the pattern, or an entry the roster clerk made. */
public enum RoleSource implements WireValue {
    PATTERN("pattern"),
    OVERRIDE("override");

    private final String wire;

    RoleSource(String wire) {
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
}
