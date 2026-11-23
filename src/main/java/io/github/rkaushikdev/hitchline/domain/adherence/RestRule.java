package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/**
 * The four ways a log of rest breaks the rest hour rules: too little in a day, too little in a
 * week, rest cut into too many pieces, or no piece of it long enough to sleep in.
 */
public enum RestRule implements WireValue {
    SHORT_DAY("short_day"),
    SHORT_WEEK("short_week"),
    TOO_MANY_PERIODS("too_many_periods"),
    NO_LONG_PERIOD("no_long_period");

    private final String wire;

    RestRule(String wire) {
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

    public static Optional<RestRule> fromWire(String wire) {
        return WireValue.fromWire(RestRule.class, wire);
    }
}
