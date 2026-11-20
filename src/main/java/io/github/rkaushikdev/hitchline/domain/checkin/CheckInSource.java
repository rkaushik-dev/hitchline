package io.github.rkaushikdev.hitchline.domain.checkin;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/**
 * How a check in reached us. The source matters downstream: a kiosk tap on the platform is
 * trusted for the day it names, while an import can carry any date at all and is held to the
 * backfill rules.
 */
public enum CheckInSource implements WireValue {
    APP("app"),
    WATCH("watch"),
    KIOSK("kiosk"),
    IMPORT("import"),
    SUPERVISOR("supervisor");

    private final String wire;

    CheckInSource(String wire) {
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

    public static Optional<CheckInSource> fromWire(String wire) {
        return WireValue.fromWire(CheckInSource.class, wire);
    }

    public static boolean isCheckInSource(String value) {
        return fromWire(value).isPresent();
    }
}
