package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.util.Numbers;

/**
 * A day or a week of logged rest that breaks a rest hour rule.
 *
 * @param rule which rule
 * @param range the day, or the seven days, it covers
 * @param hours the rest logged over the range
 * @param periods how many pieces the rest was logged in
 */
public record RestBreach(RestRule rule, DateRange range, double hours, int periods) {

    /** One line, such as {@code short_day 2025-01-08..2025-01-08: 8.5 hours in 1 period}. */
    public String describe() {
        String pieces = periods == 1 ? " period" : " periods";
        return rule + " " + range + ": " + Numbers.format(hours) + " hours in " + periods + pieces;
    }
}
