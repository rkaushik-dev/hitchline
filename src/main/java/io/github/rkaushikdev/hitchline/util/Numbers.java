package io.github.rkaushikdev.hitchline.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Number text. Reports, messages and stored documents write numbers in one fixed form: a whole
 * number has no decimal point, any other value is the shortest decimal that reads back as the
 * same IEEE 754 double, and exponent notation is used only outside {@code 1e-6 <= |x| < 1e21}.
 */
public final class Numbers {

    private static final double EXPONENT_ABOVE = 1e21;
    private static final double EXPONENT_BELOW = 1e-6;

    private Numbers() {}

    /** The text of {@code value}, such as {@code 3}, {@code 0.25}, {@code 1e+21} or {@code NaN}. */
    public static String format(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        if (value == 0) {
            return "0";
        }
        double magnitude = Math.abs(value);
        String sign = value < 0 ? "-" : "";
        BigDecimal shortest = new BigDecimal(Double.toString(magnitude)).stripTrailingZeros();
        if (magnitude >= EXPONENT_ABOVE || magnitude < EXPONENT_BELOW) {
            String digits = shortest.unscaledValue().toString();
            int exponent = digits.length() - 1 - shortest.scale();
            String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + Math.abs(exponent);
        }
        return sign + shortest.toPlainString();
    }

    /** The text of a count or any other whole number. */
    public static String format(long value) {
        return Long.toString(value);
    }

    /**
     * {@code value} to exactly {@code places} decimals. The exact binary value is rounded, with a
     * tie going away from zero, so 1.005 (stored just below 1.005) gives {@code 1.00}. A negative
     * value that rounds to zero keeps its sign.
     */
    public static String toFixed(double value, int places) {
        if (places < 0 || places > 100) {
            throw new IllegalArgumentException("places must be between 0 and 100, got " + places);
        }
        if (Double.isNaN(value) || Double.isInfinite(value) || Math.abs(value) >= EXPONENT_ABOVE) {
            return format(value);
        }
        String sign = value < 0 ? "-" : "";
        return sign
                + new BigDecimal(Math.abs(value))
                        .setScale(places, RoundingMode.HALF_UP)
                        .toPlainString();
    }

    /** Rounds to the nearest whole number, a tie going towards positive infinity. */
    public static long round(double value) {
        return (long) Math.floor(value + 0.5);
    }

    /** Rounds to {@code places} decimals the way {@code Math.round(x * 10^p) / 10^p} does. */
    public static double roundTo(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.floor(value * scale + 0.5) / scale;
    }
}
