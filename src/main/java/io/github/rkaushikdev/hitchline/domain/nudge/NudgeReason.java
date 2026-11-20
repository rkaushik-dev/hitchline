package io.github.rkaushikdev.hitchline.domain.nudge;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** Why somebody is being pinged. The reason is carried through to the message, because a person who has already done the thing twice today should not read the same words as one who has not started. */
public enum NudgeReason implements WireValue {
    NOTHING_YET("nothing_yet"),
    PART_WAY("part_way"),
    LAST_CHANCE("last_chance");

    private final String wire;

    NudgeReason(String wire) {
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

    public static Optional<NudgeReason> fromWire(String wire) {
        return WireValue.fromWire(NudgeReason.class, wire);
    }
}
