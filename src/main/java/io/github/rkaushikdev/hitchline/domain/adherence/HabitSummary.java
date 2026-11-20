package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.domain.time.DateRange;

/**
 * A window summarised for one habit.
 *
 * @param habitId the habit
 * @param range the window
 * @param owed obligations of the habit that fell due inside it
 * @param met how many were met
 * @param partial how many closed short
 * @param missed how many closed with nothing done
 * @param pending how many are still open
 * @param shieldedDays the member's shielded days inside the window
 * @param rate the adherence rate over the settled obligations
 */
public record HabitSummary(
        String habitId,
        DateRange range,
        int owed,
        int met,
        int partial,
        int missed,
        int pending,
        int shieldedDays,
        double rate) {

    static HabitSummary of(String habitId, PeriodSummary summary) {
        return new HabitSummary(
                habitId,
                summary.range(),
                summary.owed(),
                summary.met(),
                summary.partial(),
                summary.missed(),
                summary.pending(),
                summary.shieldedDays(),
                summary.rate());
    }

    /** The same figures without the habit. */
    public PeriodSummary summary() {
        return new PeriodSummary(range, owed, met, partial, missed, pending, shieldedDays, rate);
    }
}
