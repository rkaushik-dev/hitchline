package io.github.rkaushikdev.hitchline.infra.store;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** The kinds of record the journal holds, by the word each line carries. */
public enum RecordKind implements WireValue {
    MEMBER("member"),
    HABIT("habit"),
    CHECKIN("checkin"),
    OVERRIDE("override"),
    SHIELD("shield"),
    CREW("crew"),
    MEMBERSHIP("membership"),
    SHARING("sharing"),
    POLICY("policy");

    private final String wire;

    RecordKind(String wire) {
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

    public static Optional<RecordKind> fromWire(String wire) {
        return WireValue.fromWire(RecordKind.class, wire);
    }

    public static boolean isRecordKind(String value) {
        return fromWire(value).isPresent();
    }
}
