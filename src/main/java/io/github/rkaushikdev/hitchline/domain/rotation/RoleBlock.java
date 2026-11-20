package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.domain.time.DateRange;

/**
 * An unbroken run of days sharing one role.
 *
 * @param role the role of every day in the run
 * @param range the days of the run
 */
public record RoleBlock(DayRole role, DateRange range) {

    /** The block as {@code role from..to}, which is how reports list them. */
    public String describe() {
        return role + " " + range;
    }
}
