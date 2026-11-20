package io.github.rkaushikdev.hitchline.domain.habit;

import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * New terms for a habit, from a date. Without a grace window the one in force carries forward.
 *
 * @param from the first day the new terms apply
 * @param cadence how often it is owed from then
 * @param target what counts as done from then
 * @param graceDays how late a check in still counts, when it changes
 * @param note why it changed, at most 240 characters once trimmed
 */
public record RevisionInput(
        CivilDate from, Cadence cadence, Optional<HabitTarget> target, OptionalInt graceDays, String note) {

    public RevisionInput {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(cadence, "cadence");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(graceDays, "graceDays");
        note = note == null ? "" : note;
    }

    public static RevisionInput of(CivilDate from, Cadence cadence) {
        return new RevisionInput(from, cadence, Optional.empty(), OptionalInt.empty(), "");
    }

    public RevisionInput withTarget(HabitTarget value) {
        return new RevisionInput(from, cadence, Optional.of(value), graceDays, note);
    }

    public RevisionInput withTarget(Optional<HabitTarget> value) {
        return new RevisionInput(from, cadence, value, graceDays, note);
    }

    public RevisionInput withGraceDays(int value) {
        return new RevisionInput(from, cadence, target, OptionalInt.of(value), note);
    }

    public RevisionInput withNote(String value) {
        return new RevisionInput(from, cadence, target, graceDays, value);
    }
}
