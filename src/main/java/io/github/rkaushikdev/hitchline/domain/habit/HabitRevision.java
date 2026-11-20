package io.github.rkaushikdev.hitchline.domain.habit;

import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import java.util.Optional;

/**
 * What a habit asked for, and from when. Changing a habit used to rewrite it, which quietly
 * rewrote history too: a report for January would be scored against a cadence that only started
 * in March. A revision is dated, and a report reads the revision that was in force on the day.
 *
 * @param habitId the habit
 * @param version 1 for the terms it was created with, then one more for each revision
 * @param from the first day these terms applied
 * @param cadence how often it was owed
 * @param target what counted as done
 * @param graceDays how late a check in still counted
 * @param note why it changed
 */
public record HabitRevision(
        String habitId,
        int version,
        CivilDate from,
        Cadence cadence,
        Optional<HabitTarget> target,
        int graceDays,
        String note) {

    /** One line, such as {@code v2 from 2025-03-01: 1/day, at least 20 minutes (moved)}. */
    public String describe() {
        String written = target.map(value -> ", " + value.format()).orElse("");
        String said = note.isEmpty() ? "" : " (" + note + ")";
        return "v" + version + " from " + from + ": " + cadence.format() + written + said;
    }
}
