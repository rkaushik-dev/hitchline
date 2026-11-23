package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How many days of a window somebody spent on each clock. People working abroad on a rota are
 * asked how many days they spent in each country, for tax residence and for the day counts a
 * seafarer's deduction is claimed on, and the clock a rotation day is measured on is where they
 * were that day: the site on shift, home off shift, and wherever a travel day led.
 *
 * @param window the days counted
 * @param zones the days on each clock, most days first, then by zone
 */
public record Presence(DateRange window, List<ZoneDays> zones) {

    public Presence {
        zones = List.copyOf(zones);
    }

    /**
     * The days on one clock.
     *
     * @param zone the clock
     * @param days how many days of the window were measured on it
     * @param share those days over the whole window
     */
    public record ZoneDays(String zone, int days, double share) {}

    /** Every day of {@code window} placed on its clock and counted. */
    public static Presence over(Roster roster, DateRange window) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CivilDate date : window.days()) {
            counts.merge(Zoning.zoneOn(roster, date).zone(), 1, Integer::sum);
        }
        List<ZoneDays> zones = new ArrayList<>();
        counts.forEach((zone, days) -> zones.add(new ZoneDays(zone, days, days / (double) window.length())));
        zones.sort(Comparator.comparingInt(ZoneDays::days).reversed().thenComparing(ZoneDays::zone));
        return new Presence(window, zones);
    }

    /** The days measured on {@code zone}, nothing when none were. */
    public int daysIn(String zone) {
        return zones.stream()
                .filter(entry -> entry.zone().equals(zone))
                .mapToInt(ZoneDays::days)
                .sum();
    }
}
