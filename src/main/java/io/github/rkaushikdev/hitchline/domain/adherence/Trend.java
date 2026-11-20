package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** Which way the latest window moved against the ones before it, or unknown when there are too few to say. */
public enum Trend implements WireValue {
    RISING("rising"),
    STEADY("steady"),
    FALLING("falling"),
    UNKNOWN("unknown");

    private final String wire;

    Trend(String wire) {
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

    public static Optional<Trend> fromWire(String wire) {
        return WireValue.fromWire(Trend.class, wire);
    }

    /** What the trend means, in words. */
    public String describe() {
        return switch (this) {
            case RISING -> "better than the hitches before it";
            case FALLING -> "worse than the hitches before it";
            case STEADY -> "about the same as the hitches before it";
            case UNKNOWN -> "not enough hitches to say";
        };
    }
}
