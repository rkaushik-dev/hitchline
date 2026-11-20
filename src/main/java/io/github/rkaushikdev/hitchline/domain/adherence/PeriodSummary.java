package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.util.Numbers;

/**
 * What a window looked like once every obligation in it was scored. The counts are kept beside
 * the rate because a rate on its own hides how much was owed, and one missed day out of two reads
 * the same as fifty out of a hundred.
 *
 * @param range the window
 * @param owed obligations that fell due inside it
 * @param met how many were met
 * @param partial how many closed short
 * @param missed how many closed with nothing done
 * @param pending how many are still open
 * @param shieldedDays the member's shielded days inside the window
 * @param rate the adherence rate over the settled obligations
 */
public record PeriodSummary(
        DateRange range, int owed, int met, int partial, int missed, int pending, int shieldedDays, double rate) {

    /** True when nothing was missed and nothing closed short. */
    public boolean isClean() {
        return missed == 0 && partial == 0;
    }

    /** One line, such as {@code 2025-01-01..2025-01-31 2 of 4 at 50%}. */
    public String describe() {
        return range + " " + met + " of " + owed + " at " + Numbers.round(rate * 100) + "%";
    }
}
