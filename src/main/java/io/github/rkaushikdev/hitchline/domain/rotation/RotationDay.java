package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.domain.time.CivilDate;

/**
 * A day of somebody's rotation, with the clock it is measured on.
 *
 * @param date the rotation day
 * @param role what the day is
 * @param zone the clock the day is measured on
 * @param reason why that clock
 * @param straddled true when the site and home clocks disagreed about which date it was
 */
public record RotationDay(CivilDate date, DayRole role, String zone, ZoneReason reason, boolean straddled) {

    /** The day as one report line, such as {@code 2025-01-10 onshift on Europe/Oslo as site}. */
    public String describe() {
        return date + " " + role + " on " + zone + " as " + reason + (straddled ? " straddled" : "");
    }
}
