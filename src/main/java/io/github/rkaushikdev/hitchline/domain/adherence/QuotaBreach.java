package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;

/**
 * A stretch where more was dropped than a quota allows.
 *
 * @param quota the rule broken
 * @param window the days the stretch covers, starting on its first failure
 * @param counted the failures inside it
 * @param firstOn the first failure
 */
public record QuotaBreach(Quota quota, DateRange window, int counted, CivilDate firstOn) {

    /** One line, such as {@code tight broken 2025-01-06 to 2025-01-12 with 2}. */
    public String describe() {
        return quota.name() + " broken " + window.from() + " to " + window.to() + " with " + counted;
    }
}
