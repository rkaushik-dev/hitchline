package io.github.rkaushikdev.hitchline.domain.crew;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** Who may read somebody's adherence for a habit: only them, them and their crew lead, or their whole crew. */
public enum Visibility implements WireValue {
    PRIVATE("private"),
    LEAD_ONLY("lead_only"),
    CREW("crew");

    private final String wire;

    Visibility(String wire) {
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

    public static Optional<Visibility> fromWire(String wire) {
        return WireValue.fromWire(Visibility.class, wire);
    }

    public static boolean isVisibility(String value) {
        return fromWire(value).isPresent();
    }

    /** The setting in plain words, for somebody choosing one. */
    public String describe() {
        return switch (this) {
            case PRIVATE -> "only you";
            case LEAD_ONLY -> "you and your crew lead";
            case CREW -> "everybody on your crew";
        };
    }
}
