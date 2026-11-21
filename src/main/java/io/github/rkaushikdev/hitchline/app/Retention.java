package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.util.ArrayList;
import java.util.List;

/**
 * How long records are kept. An operator has a retention schedule whether or not a tool has one,
 * and a check in note can hold something a person would rather was not on a disk five years
 * later. Nothing here deletes anything on its own: it says what is old, and a person runs the
 * prune.
 *
 * @param keepMonths whole check ins older than this are candidates to go
 * @param keepNoteMonths notes are cleared earlier than the rows themselves
 * @param floorMonths never touch anything newer than this, whatever else the policy says
 */
public record Retention(int keepMonths, int keepNoteMonths, int floorMonths) {

    /** Five years for rows, two for notes, and nothing younger than a year touched. */
    public static final Retention DEFAULT_RETENTION = new Retention(60, 24, 12);

    public Retention {
        check("keepMonths", keepMonths);
        check("keepNoteMonths", keepNoteMonths);
        check("floorMonths", floorMonths);
        if (keepNoteMonths > keepMonths) {
            throw ValidationException.field("keepNoteMonths", "cannot be longer than the row is kept");
        }
        if (floorMonths > keepNoteMonths) {
            throw ValidationException.field("floorMonths", "cannot be longer than notes are kept");
        }
    }

    private static void check(String name, int value) {
        if (value < 1 || value > 600) {
            throw ValidationException.field(name, "must be a whole number of months from 1 to 600");
        }
    }

    public Retention withKeepMonths(int value) {
        return new Retention(value, keepNoteMonths, floorMonths);
    }

    public Retention withKeepNoteMonths(int value) {
        return new Retention(keepMonths, value, floorMonths);
    }

    public Retention withFloorMonths(int value) {
        return new Retention(keepMonths, keepNoteMonths, value);
    }

    /**
     * What a prune would do.
     *
     * @param cutoff rows from before this day go
     * @param noteCutoff notes from before this day are cleared
     * @param expired the rows that would go
     * @param toRedact the rows whose notes would be cleared
     * @param kept how many rows stay as they are
     */
    public record RetentionPlan(
            CivilDate cutoff, CivilDate noteCutoff, List<CheckIn> expired, List<CheckIn> toRedact, int kept) {

        public RetentionPlan {
            expired = List.copyOf(expired);
            toRedact = List.copyOf(toRedact);
        }

        /** One line, such as {@code 1 to remove, 2 notes to clear, 3 kept}. */
        public String describe() {
            return expired.size() + " to remove, " + toRedact.size() + " notes to clear, " + kept + " kept";
        }
    }

    /**
     * Works out what a prune would do without doing it, reading each check in's day on
     * {@code zone}. The floor is applied first so a policy that has been edited into nonsense still
     * cannot reach anything recent.
     */
    public RetentionPlan plan(List<CheckIn> entries, CivilDate today, String zone) {
        CivilDate cutoff = today.plusMonths(-keepMonths);
        CivilDate noteCutoff = today.plusMonths(-keepNoteMonths);
        CivilDate floor = today.plusMonths(-floorMonths);
        List<CheckIn> expired = new ArrayList<>();
        List<CheckIn> toRedact = new ArrayList<>();
        int kept = 0;
        for (CheckIn entry : entries) {
            CivilDate on = Zones.civilDateIn(zone, entry.at());
            if (!on.isBefore(floor)) {
                kept += 1;
            } else if (on.isBefore(cutoff)) {
                expired.add(entry);
            } else if (on.isBefore(noteCutoff) && !entry.note().isEmpty()) {
                toRedact.add(entry);
            } else {
                kept += 1;
            }
        }
        return new RetentionPlan(cutoff, noteCutoff, expired, toRedact, kept);
    }

    /** The check in with its note cleared. */
    public static CheckIn redact(CheckIn entry) {
        return entry.withNote("");
    }
}
