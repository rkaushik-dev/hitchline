package io.github.rkaushikdev.hitchline.domain.habit;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Numbers;
import io.github.rkaushikdev.hitchline.util.Texts;
import io.github.rkaushikdev.hitchline.util.WireValue;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * What a reading is measured in. Each unit keeps readings to a fixed number of decimal places:
 * none for counts, minutes, grams and pages, one for hours, two for kilometres and litres. Hours
 * are kept to the tenth because rest hours are logged in half hours and quarter hours, and
 * rounding them to the hour would move somebody across the ten hour line either way.
 */
public enum TargetUnit implements WireValue {
    COUNT("count", 0, "times"),
    MINUTES("minutes", 0, "min", "mins"),
    HOURS("hours", 1, "h", "hr", "hrs", "hour"),
    KILOMETRES("kilometres", 2, "km"),
    LITRES("litres", 2, "l"),
    GRAMS("grams", 0, "g"),
    PAGES("pages", 0);

    private static final Map<String, TargetUnit> ALIASES = aliases();

    private final String wire;
    private final int places;
    private final List<String> shortWords;

    TargetUnit(String wire, int places, String... shortWords) {
        this.wire = wire;
        this.places = places;
        this.shortWords = List.of(shortWords);
    }

    /** Every word a unit answers to: its own name and the short words people type for it. */
    private static Map<String, TargetUnit> aliases() {
        Map<String, TargetUnit> words = new HashMap<>();
        for (TargetUnit unit : values()) {
            words.put(unit.wire, unit);
            unit.shortWords.forEach(word -> words.put(word, unit));
        }
        return Map.copyOf(words);
    }

    @Override
    public String wire() {
        return wire;
    }

    @Override
    public String toString() {
        return wire;
    }

    /**
     * The most one day can hold in this unit, for a day of {@code dayMinutes} minutes: the day
     * itself for minutes and hours, nothing for a unit that is not a length of time.
     */
    public OptionalDouble dayCeiling(int dayMinutes) {
        return switch (this) {
            case MINUTES -> OptionalDouble.of(dayMinutes);
            case HOURS -> OptionalDouble.of(dayMinutes / 60.0);
            default -> OptionalDouble.empty();
        };
    }

    /** How many decimal places a reading in this unit is kept to. */
    public int places() {
        return places;
    }

    public static Optional<TargetUnit> fromWire(String wire) {
        return WireValue.fromWire(TargetUnit.class, wire);
    }

    public static boolean isTargetUnit(String value) {
        return fromWire(value).isPresent();
    }

    /** Reads the words people type for a unit, such as {@code min}, {@code KM} or {@code times}. */
    public static TargetUnit unitFor(String word) {
        TargetUnit unit = ALIASES.get(Texts.trim(word).toLowerCase(Locale.ROOT));
        if (unit == null) {
            throw ValidationException.field("unit", "is not a unit this tracks: " + word);
        }
        return unit;
    }

    /**
     * Rounds a reading to this unit's places, a tie going towards positive infinity. The decimal
     * point is shifted in the reading's decimal text rather than by multiplying, which keeps
     * readings such as 1.005 litres off the wrong side of the rounding.
     */
    public double round(double amount) {
        if (places == 0) {
            return Numbers.round(amount);
        }
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            return amount;
        }
        double shifted =
                new BigDecimal(Numbers.format(amount)).movePointRight(places).doubleValue();
        long whole = Numbers.round(shifted);
        return BigDecimal.valueOf(whole).movePointLeft(places).doubleValue();
    }

    /** Adds readings and rounds once at the end. */
    public double sum(List<Double> readings) {
        double total = 0;
        for (double reading : readings) {
            total += reading;
        }
        return round(total);
    }
}
