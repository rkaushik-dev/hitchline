package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** Why days are shielded. A member may declare annual leave or sickness themselves; every other reason needs a supervisor. */
public enum ShieldReason implements WireValue {
    ANNUAL_LEAVE("annual_leave"),
    SICK("sick"),
    COMPASSIONATE("compassionate"),
    TRAINING("training"),
    MEDICAL_ADVICE("medical_advice"),
    OPERATIONAL("operational");

    private final String wire;

    ShieldReason(String wire) {
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

    public static Optional<ShieldReason> fromWire(String wire) {
        return WireValue.fromWire(ShieldReason.class, wire);
    }
}
