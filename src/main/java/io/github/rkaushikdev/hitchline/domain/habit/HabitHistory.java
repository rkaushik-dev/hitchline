package io.github.rkaushikdev.hitchline.domain.habit;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every set of terms a habit has had, oldest first. Revising never changes a history; it hands
 * back a longer one.
 *
 * @param habitId the habit
 * @param revisions the terms in force from each revision's start, in order
 */
public record HabitHistory(String habitId, List<HabitRevision> revisions) {

    static final int NOTE_MAX = 240;

    public HabitHistory {
        revisions = List.copyOf(revisions);
    }

    /** The history a habit starts with: one revision from the day it began. */
    public static HabitHistory of(Habit habit) {
        return new HabitHistory(
                habit.id(),
                List.of(new HabitRevision(
                        habit.id(),
                        1,
                        habit.startsOn(),
                        habit.cadence(),
                        habit.target(),
                        habit.graceDays(),
                        "as created")));
    }

    /**
     * Adds new terms from {@code input.from()}, which must be after the start of the terms in force.
     * The grace window carries forward when the input leaves it out.
     */
    public HabitHistory revise(RevisionInput input) {
        HabitRevision last =
                latest().orElseThrow(() -> new ConflictException("habit " + habitId + " has no revisions to follow"));
        if (input.from().isBefore(last.from())) {
            throw ValidationException.field("from", "is before the revision in force since " + last.from());
        }
        if (CivilDate.compare(input.from(), last.from()) == 0) {
            throw new ConflictException("a revision already starts on " + input.from());
        }
        String note = Texts.trim(input.note());
        if (note.length() > NOTE_MAX) {
            throw ValidationException.field("note", "must be at most " + NOTE_MAX + " characters");
        }
        int graceDays = input.graceDays().orElse(last.graceDays());
        if (graceDays < 0 || graceDays > Habit.MAX_GRACE_DAYS) {
            throw ValidationException.field("graceDays", "must be between 0 and 7");
        }
        List<HabitRevision> next = new ArrayList<>(revisions);
        next.add(new HabitRevision(
                habitId, last.version() + 1, input.from(), input.cadence(), input.target(), graceDays, note));
        return new HabitHistory(habitId, next);
    }

    /** The revision in force on a date, or nothing when the habit had not started. */
    public Optional<HabitRevision> effectiveOn(CivilDate date) {
        HabitRevision found = null;
        for (HabitRevision revision : revisions) {
            if (date.isBefore(revision.from())) {
                break;
            }
            found = revision;
        }
        return Optional.ofNullable(found);
    }

    public Optional<HabitRevision> latest() {
        return revisions.isEmpty() ? Optional.empty() : Optional.of(revisions.get(revisions.size() - 1));
    }

    public int versionCount() {
        return revisions.size();
    }

    /**
     * Applies the revision in force on a date to a habit, for scoring that day. Before any
     * revision applies the habit comes back unchanged, the same object.
     */
    public Habit asOf(Habit habit, CivilDate date) {
        return effectiveOn(date)
                .map(revision -> habit.withTerms(revision.cadence(), revision.target(), revision.graceDays()))
                .orElse(habit);
    }
}
