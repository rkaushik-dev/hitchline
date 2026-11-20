package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/**
 * Why a run of days does not match the pattern. The reason is kept because a supervisor reading
 * the roster wants to know whether somebody was held over or called out, not merely that the day
 * changed colour.
 */
public enum OverrideReason implements WireValue {
    HELD_OVER("held_over"),
    CALLED_OUT("called_out"),
    SWAPPED("swapped"),
    MEDICAL("medical"),
    TRAINING("training"),
    WEATHER("weather");

    private final String wire;

    OverrideReason(String wire) {
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

    public static Optional<OverrideReason> fromWire(String wire) {
        return WireValue.fromWire(OverrideReason.class, wire);
    }
}
