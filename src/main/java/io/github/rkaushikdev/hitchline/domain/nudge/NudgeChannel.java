package io.github.rkaushikdev.hitchline.domain.nudge;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** How a nudge reaches somebody: a push to the handset, an email, the platform tannoy, or not at all. */
public enum NudgeChannel implements WireValue {
    PUSH("push"),
    EMAIL("email"),
    TANNOY("tannoy"),
    NONE("none");

    private final String wire;

    NudgeChannel(String wire) {
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

    public static Optional<NudgeChannel> fromWire(String wire) {
        return WireValue.fromWire(NudgeChannel.class, wire);
    }
}
