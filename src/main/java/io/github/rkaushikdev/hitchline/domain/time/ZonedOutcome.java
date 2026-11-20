package io.github.rkaushikdev.hitchline.domain.time;

/**
 * What happened when a wall clock time was pushed back onto the timeline: {@code skipped} is a
 * spring forward gap and {@code ambiguous} an autumn overlap.
 */
public enum ZonedOutcome {
    EXACT("exact"),
    SKIPPED("skipped"),
    AMBIGUOUS("ambiguous");

    private final String wire;

    ZonedOutcome(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
