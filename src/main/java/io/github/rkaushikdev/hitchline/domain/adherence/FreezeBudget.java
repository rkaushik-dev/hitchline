package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.domain.ValidationException;

/**
 * How many misses a run may carry. A shield is granted for a reason somebody signs off; a freeze
 * is spent by the person themselves and there are only so many. The difference matters: a shield
 * says nothing was owed, a freeze says something was owed and missed but the run survives it.
 *
 * @param allowance how many misses a run may absorb inside the window, from 0 to 10
 * @param windowDays days the allowance is counted over, ending on the day being scored, 1 to 365
 */
public record FreezeBudget(int allowance, int windowDays) {

    /** One miss in any 28 days. */
    public static final FreezeBudget DEFAULT = new FreezeBudget(1, 28);

    public FreezeBudget {
        if (allowance < 0 || allowance > 10) {
            throw ValidationException.field("allowance", "must be a whole number from 0 to 10");
        }
        if (windowDays < 1 || windowDays > 365) {
            throw ValidationException.field("windowDays", "must be a whole number of days from 1 to 365");
        }
    }

    public static FreezeBudget of(int allowance, int windowDays) {
        return new FreezeBudget(allowance, windowDays);
    }

    public FreezeBudget withAllowance(int value) {
        return new FreezeBudget(value, windowDays);
    }

    public FreezeBudget withWindowDays(int value) {
        return new FreezeBudget(allowance, value);
    }
}
