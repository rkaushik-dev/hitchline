package io.github.rkaushikdev.hitchline.domain.habit;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Numbers;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What counts as having done the thing. A habit with no target is satisfied by turning up; one
 * with a target needs the readings to add up. The amount is positive, at most 100000, and already
 * rounded to the unit.
 *
 * @param unit what the readings are measured in
 * @param amount the floor or ceiling
 * @param comparison whether the amount is a floor or a ceiling
 */
public record HabitTarget(TargetUnit unit, double amount, TargetComparison comparison) {

    static final int MAX_AMOUNT = 100_000;

    private static final Pattern TARGET = Pattern.compile(
            "(at least|at most)" + Texts.SPACE_CLASS + "+([0-9]+(?:\\.[0-9]+)?)" + Texts.SPACE_CLASS + "*([a-z]+)");

    public HabitTarget {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(comparison, "comparison");
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) {
            throw ValidationException.field("amount", "must be a positive number, got " + Numbers.format(amount));
        }
        if (amount > MAX_AMOUNT) {
            throw ValidationException.field(
                    "amount", "must be at most " + MAX_AMOUNT + ", got " + Numbers.format(amount));
        }
        double rounded = unit.round(amount);
        if (rounded <= 0) {
            throw ValidationException.field("amount", "rounds to nothing in " + unit);
        }
        amount = rounded;
    }

    /** An at least target. */
    public static HabitTarget of(TargetUnit unit, double amount) {
        return new HabitTarget(unit, amount, TargetComparison.AT_LEAST);
    }

    public static HabitTarget of(TargetUnit unit, double amount, TargetComparison comparison) {
        return new HabitTarget(unit, amount, comparison);
    }

    /** Reads "at least 30 minutes" and "at most 2 litres". */
    public static HabitTarget parse(String text) {
        Matcher match = TARGET.matcher(Texts.trim(text).toLowerCase(Locale.ROOT));
        if (!match.matches()) {
            throw ValidationException.field("target", "must look like at least 30 minutes, got " + text);
        }
        TargetComparison comparison =
                match.group(1).equals("at most") ? TargetComparison.AT_MOST : TargetComparison.AT_LEAST;
        return new HabitTarget(TargetUnit.unitFor(match.group(3)), Double.parseDouble(match.group(2)), comparison);
    }

    /** The target as it is written, such as {@code at least 30 minutes}. */
    public String format() {
        String words = comparison == TargetComparison.AT_MOST ? "at most" : "at least";
        return words + " " + Numbers.format(amount) + " " + unit;
    }

    /** True when the total, rounded to the unit, is on the right side of the amount. */
    public boolean isMetBy(double total) {
        double rounded = unit.round(total);
        return comparison == TargetComparison.AT_LEAST ? rounded >= amount : rounded <= amount;
    }

    /**
     * How far along a total is, capped at one. An at most target reads as fully met until it is
     * breached, at which point it drops to zero.
     */
    public double progress(double total) {
        double rounded = unit.round(total);
        if (comparison == TargetComparison.AT_MOST) {
            return rounded <= amount ? 1 : 0;
        }
        return Math.min(1, Math.max(0, rounded / amount));
    }

    @Override
    public String toString() {
        return format();
    }
}
