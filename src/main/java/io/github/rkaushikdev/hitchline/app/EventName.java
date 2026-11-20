package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** The things services announce. */
public enum EventName implements WireValue {
    HABIT_CREATED("habit.created"),
    HABIT_CHANGED("habit.changed"),
    HABIT_ARCHIVED("habit.archived"),
    CHECKIN_RECORDED("checkin.recorded"),
    CHECKIN_VOIDED("checkin.voided"),
    ROSTER_HELD("roster.held"),
    SHIELD_GRANTED("shield.granted"),
    CREW_ENROLLED("crew.enrolled");

    private final String wire;

    EventName(String wire) {
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

    public static Optional<EventName> fromWire(String wire) {
        return WireValue.fromWire(EventName.class, wire);
    }
}
