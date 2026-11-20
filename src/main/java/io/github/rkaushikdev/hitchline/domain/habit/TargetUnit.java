package io.github.rkaushikdev.hitchline.domain.habit;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Numbers;
import io.github.rkaushikdev.hitchline.util.Texts;
import io.github.rkaushikdev.hitchline.util.WireValue;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What a reading is measured in. Each unit keeps readings to a fixed number of decimal places:
 * none for counts, minutes, grams and pages, two for kilometres and litres.
 */
public enum TargetUnit implements WireValue {
    COUNT("count", 0),
    MINUTES("minutes", 0),
    KILOMETRES("kilometres", 2),
    LITRES("litres", 2),
    GRAMS("grams", 0),
    PAGES("pages", 0);

    private static final Map<String, TargetUnit> ALIASES = Map.ofEntries(
            Map.entry("count", COUNT),
            Map.entry("times", COUNT),
            Map.entry("min", MINUTES),
            Map.entry("mins", MINUTES),
            Map.entry("minutes", MINUTES),
            Map.entry("km", KILOMETRES),
            Map.entry("kilometres", KILOMETRES),
            Map.entry("l", LITRES),
            Map.entry("litres", LITRES),
            Map.entry("g", GRAMS),
            Map.entry("grams", GRAMS),
            Map.entry("pages", PAGES));

    private final String wire;
    private final int places;

    TargetUnit(String wire, int places) {
        this.wire = wire;
        this.places = places;
    }

    @Override
    public String wire() {
        return wire;
    }

    @Override
    public String toString() {
        return wire;
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
