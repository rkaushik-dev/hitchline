package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.domain.time.DateRange;

/**
 * A stretch of a roster that breaks one of the duty limits.
 *
 * @param rule which limit
 * @param range the trip or the break, whole
 * @param days how long it runs
 * @param limit the most days a trip may run, or the fewest a break may
 */
public record DutyBreach(DutyRule rule, DateRange range, int days, int limit) {

    /** One line, such as {@code long_trip 2025-01-06..2025-01-29: 24 days away, limit 21}. */
    public String describe() {
        String what = rule == DutyRule.LONG_TRIP ? " days away, limit " : " days at home, at least ";
        return rule + " " + range + ": " + days + what + limit;
    }
}
