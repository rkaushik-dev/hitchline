package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.ArrayList;
import java.util.List;

/**
 * How long somebody may be kept away and how short a break at home may be. Fatigue guidance for
 * offshore work puts a trip at 21 days at most, and a crew change that sends somebody back after a
 * few days ashore is the other half of the same problem. A trip is every unbroken run of on shift
 * and travel days, because a travel day is a day away from home whatever the pattern calls it; a
 * break is the run of home days between two trips.
 *
 * @param maxTripDays the longest a trip may run, 1 to 60 days
 * @param minBreakDays the shortest a break between two trips may be, 0 to 60 days
 */
public record DutyLimits(int maxTripDays, int minBreakDays) {

    static final int MAX_DAYS = 60;

    /** At most 21 days away and at least 7 at home between trips. */
    public static final DutyLimits DEFAULT = new DutyLimits(21, 7);

    public DutyLimits {
        if (maxTripDays < 1 || maxTripDays > MAX_DAYS) {
            throw ValidationException.field("maxTripDays", "must be between 1 and " + MAX_DAYS + " days");
        }
        if (minBreakDays < 0 || minBreakDays > MAX_DAYS) {
            throw ValidationException.field("minBreakDays", "must be between 0 and " + MAX_DAYS + " days");
        }
    }

    public static DutyLimits of(int maxTripDays, int minBreakDays) {
        return new DutyLimits(maxTripDays, minBreakDays);
    }

    /** True for the roles that keep somebody away from home. */
    public static boolean isAway(DayRole role) {
        return role == DayRole.ONSHIFT || role == DayRole.TRAVEL;
    }

    /**
     * The trips and breaks of {@code roster} touching {@code window} that break a limit, in order.
     * Runs are read over a window widened by a cycle and both limits at each end, so a trip that
     * started before the window is measured whole.
     */
    public List<DutyBreach> check(Roster roster, DateRange window) {
        int reach = roster.member().rotation().pattern().cycleLength() + maxTripDays + minBreakDays;
        DateRange wide =
                DateRange.of(window.from().plusDays(-reach), window.to().plusDays(reach));
        List<RoleBlock> runs = awayRuns(roster.effectiveBlocks(wide));
        List<DutyBreach> breaches = new ArrayList<>();
        for (int index = 0; index < runs.size(); index += 1) {
            RoleBlock run = runs.get(index);
            if (!run.range().intersects(window)) {
                continue;
            }
            boolean whole = index > 0 && index < runs.size() - 1;
            if (run.role() == DayRole.ONSHIFT && run.days() > maxTripDays) {
                breaches.add(new DutyBreach(DutyRule.LONG_TRIP, run.range(), run.days(), maxTripDays));
            }
            if (run.role() == DayRole.OFFSHIFT && whole && run.days() < minBreakDays) {
                breaches.add(new DutyBreach(DutyRule.SHORT_BREAK, run.range(), run.days(), minBreakDays));
            }
        }
        return breaches;
    }

    /**
     * The blocks joined into alternating runs away and at home. An away run is labelled on shift
     * and a home run off shift, whatever mix of roles it was made of.
     */
    static List<RoleBlock> awayRuns(List<RoleBlock> blocks) {
        List<DayRole> sides = new ArrayList<>();
        for (RoleBlock block : blocks) {
            DayRole side = isAway(block.role()) ? DayRole.ONSHIFT : DayRole.OFFSHIFT;
            for (int day = 0; day < block.days(); day += 1) {
                sides.add(side);
            }
        }
        return blocks.isEmpty()
                ? List.of()
                : RoleBlock.runsOf(blocks.get(0).range().from(), sides);
    }
}
