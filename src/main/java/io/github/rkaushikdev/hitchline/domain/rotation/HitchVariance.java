package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One hitch as worked against the hitch as rostered. Payroll pays the days somebody was held over
 * at the day rate and a coordinator wants to know who keeps getting sent home early, so the two
 * are counted apart, along with the reasons the clerk gave for them.
 *
 * @param hitch the turn of the rotation, whole
 * @param scheduled on shift days the pattern asked for
 * @param worked on shift days once the roster entries are read in
 * @param over days on shift the pattern had down as something else
 * @param under days the pattern had on shift that were something else in the end
 * @param reasons how many changed days each reason accounts for
 */
public record HitchVariance(
        DateRange hitch, int scheduled, int worked, int over, int under, Map<OverrideReason, Integer> reasons) {

    public HitchVariance {
        Map<OverrideReason, Integer> ordered = new EnumMap<>(OverrideReason.class);
        ordered.putAll(reasons);
        reasons = Collections.unmodifiableMap(ordered);
    }

    /** Every hitch the window touches, read against the roster. */
    public static List<HitchVariance> over(Roster roster, DateRange window) {
        RotationAssignment rotation = roster.member().rotation();
        List<HitchVariance> hitches = new ArrayList<>();
        for (DateRange hitch : rotation.hitchesOver(window)) {
            int scheduled = 0;
            int worked = 0;
            int over = 0;
            int under = 0;
            Map<OverrideReason, Integer> reasons = new EnumMap<>(OverrideReason.class);
            for (CivilDate date : hitch.days()) {
                boolean planned = rotation.roleOn(date) == DayRole.ONSHIFT;
                boolean actual = roster.isOnShift(date);
                scheduled += planned ? 1 : 0;
                worked += actual ? 1 : 0;
                over += actual && !planned ? 1 : 0;
                under += planned && !actual ? 1 : 0;
                if (planned != actual) {
                    Optional<RosterOverride> entry = RosterOverride.overrideOn(
                            roster.overrides(), roster.member().id(), date);
                    entry.ifPresent(found -> reasons.merge(found.reason(), 1, Integer::sum));
                }
            }
            hitches.add(new HitchVariance(hitch, scheduled, worked, over, under, reasons));
        }
        return hitches;
    }

    /** True when the hitch was worked exactly as rostered. */
    public boolean asRostered() {
        return over == 0 && under == 0;
    }

    /** The reasons as one cell, such as {@code held_over 3, medical 2}, or {@code -} when there were none. */
    public String describeReasons() {
        if (reasons.isEmpty()) {
            return "-";
        }
        List<String> parts = new ArrayList<>();
        reasons.forEach((reason, days) -> parts.add(reason + " " + days));
        return String.join(", ", parts);
    }
}
