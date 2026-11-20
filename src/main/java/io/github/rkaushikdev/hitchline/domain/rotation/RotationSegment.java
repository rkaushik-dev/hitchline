package io.github.rkaushikdev.hitchline.domain.rotation;

/**
 * A run of days with one role inside a rotation pattern.
 *
 * @param role what the days are
 * @param days how many of them in a row
 */
public record RotationSegment(DayRole role, int days) {

    public static RotationSegment of(DayRole role, int days) {
        return new RotationSegment(role, days);
    }
}
