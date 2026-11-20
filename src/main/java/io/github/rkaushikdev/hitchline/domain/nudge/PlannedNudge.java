package io.github.rkaushikdev.hitchline.domain.nudge;

import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.time.Instant;

/**
 * A nudge worked out from an open obligation.
 *
 * @param habitId the habit it is about
 * @param key the obligation it is for
 * @param at when it goes out
 * @param onDate the rotation day it falls on
 * @param reason why it is being sent
 */
public record PlannedNudge(String habitId, String key, Instant at, CivilDate onDate, NudgeReason reason) {

    /** One line, such as {@code nothing_yet at 2025-01-10T19:00:00.000Z}. */
    public String describe() {
        return reason + " at " + Zones.formatInstant(at);
    }
}
