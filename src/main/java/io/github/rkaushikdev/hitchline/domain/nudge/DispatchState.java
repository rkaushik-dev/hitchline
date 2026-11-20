package io.github.rkaushikdev.hitchline.domain.nudge;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** What became of a nudge: planned, sent, seen, or dropped. Seen and dropped are final. */
public enum DispatchState implements WireValue {
    PLANNED("planned"),
    SENT("sent"),
    SEEN("seen"),
    DROPPED("dropped");

    private final String wire;

    DispatchState(String wire) {
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

    public static Optional<DispatchState> fromWire(String wire) {
        return WireValue.fromWire(DispatchState.class, wire);
    }
}
