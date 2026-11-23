package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A pattern pinned to a calendar for one person. Two people on the same rota share a pattern and
 * differ only by {@code phase}, which is how back to back crews are described: the same 14 and 14,
 * half a cycle apart.
 *
 * @param pattern the repeating cycle
 * @param anchor the date that sits at cycle position {@code phase}
 * @param phase how far into the cycle the anchor is, from 0 to one less than the cycle length
 * @param effective the days the assignment applies to, when it does not apply forever
 */
public record RotationAssignment(RotationPattern pattern, CivilDate anchor, int phase, Optional<DateRange> effective) {

    public RotationAssignment {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(effective, "effective");
        int length = pattern.cycleLength();
        if (phase < 0 || phase >= length) {
            throw ValidationException.field("phase", "must be between 0 and " + (length - 1) + ", got " + phase);
        }
    }

    public static RotationAssignment of(RotationPattern pattern, CivilDate anchor) {
        return new RotationAssignment(pattern, anchor, 0, Optional.empty());
    }

    public static RotationAssignment of(RotationPattern pattern, CivilDate anchor, int phase) {
        return new RotationAssignment(pattern, anchor, phase, Optional.empty());
    }

    public static RotationAssignment of(RotationPattern pattern, CivilDate anchor, int phase, DateRange effective) {
        return new RotationAssignment(pattern, anchor, phase, Optional.of(effective));
    }

    /**
     * Pins a pattern so that {@code crewChange} is the first day of a trip. A crewing office knows
     * the day somebody flies out far better than how far into the cycle a date happens to be.
     */
    public static RotationAssignment fromCrewChange(RotationPattern pattern, CivilDate crewChange) {
        return of(pattern, crewChange, tripStartIndex(pattern));
    }

    /**
     * Where in the cycle a trip starts: the first day away from home that follows a day at home.
     * A cycle with no day at home in it starts at zero.
     */
    public static int tripStartIndex(RotationPattern pattern) {
        for (int index = 0; index < pattern.cycleLength(); index += 1) {
            if (DutyLimits.isAway(pattern.roleAtIndex(index)) && !DutyLimits.isAway(pattern.roleAtIndex(index - 1L))) {
                return index;
            }
        }
        return 0;
    }

    /** How many days away the trip starting at {@link #tripStartIndex} runs for. */
    public static int tripLength(RotationPattern pattern) {
        int start = tripStartIndex(pattern);
        int days = 0;
        while (days < pattern.cycleLength() && DutyLimits.isAway(pattern.roleAtIndex((long) start + days))) {
            days += 1;
        }
        return days;
    }

    /**
     * The back to back of this assignment: the same pattern, starting each trip the day after this
     * one's trip ends, so the two hand the job over at every crew change.
     */
    public RotationAssignment relief() {
        int shifted = (int) Math.floorMod((long) phase - tripLength(pattern), (long) pattern.cycleLength());
        return new RotationAssignment(pattern, anchor, shifted, effective);
    }

    public boolean isActiveOn(CivilDate date) {
        return effective.map(window -> window.contains(date)).orElse(true);
    }

    private long offsetOf(CivilDate date) {
        return (long) CivilDate.daysBetween(anchor, date) + phase;
    }

    public int cycleIndexOn(CivilDate date) {
        return (int) Math.floorMod(offsetOf(date), (long) pattern.cycleLength());
    }

    public DayRole roleOn(CivilDate date) {
        return pattern.roleAtIndex(cycleIndexOn(date));
    }

    /**
     * Which repetition of the cycle the date falls in, counting the one containing the anchor as
     * zero. Negative before the anchor.
     */
    public int hitchNumberOn(CivilDate date) {
        return (int) Math.floorDiv(offsetOf(date), (long) pattern.cycleLength());
    }

    public DateRange hitchRange(int hitchNumber) {
        int length = pattern.cycleLength();
        long startOffset = (long) hitchNumber * length - phase;
        CivilDate from = anchor.plusDays(startOffset);
        return DateRange.of(from, from.plusDays(length - 1L));
    }

    public DateRange hitchRangeOn(CivilDate date) {
        return hitchRange(hitchNumberOn(date));
    }

    /** The unbroken run of days sharing the role of {@code date}. */
    public RoleBlock blockContaining(CivilDate date) {
        DayRole role = roleOn(date);
        int length = pattern.cycleLength();
        CivilDate from = date;
        for (int step = 1; step < length; step += 1) {
            CivilDate candidate = date.plusDays(-step);
            if (roleOn(candidate) != role) {
                break;
            }
            from = candidate;
        }
        CivilDate to = date;
        for (int step = 1; step < length; step += 1) {
            CivilDate candidate = date.plusDays(step);
            if (roleOn(candidate) != role) {
                break;
            }
            to = candidate;
        }
        return new RoleBlock(role, DateRange.of(from, to));
    }

    /** The first date after {@code date} whose role differs from the role on {@code date}. */
    public CivilDate nextRoleChange(CivilDate date) {
        return blockContaining(date).range().to().plusDays(1);
    }

    /** The role of every day of {@code range}, in order. */
    public List<DayRole> rolesOver(DateRange range) {
        List<DayRole> roles = new ArrayList<>();
        for (CivilDate date : range.days()) {
            roles.add(roleOn(date));
        }
        return roles;
    }

    public int countRoleOver(DateRange range, DayRole role) {
        return (int) rolesOver(range).stream().filter(entry -> entry == role).count();
    }

    /** Splits a range into the runs of same role days it is made of, clipping the ends. */
    public List<RoleBlock> blocksOver(DateRange range) {
        return RoleBlock.runsOf(range.from(), rolesOver(range));
    }

    /** One line for a log, such as {@code 2 segment cycle anchored 2025-01-06 phase 0}. */
    public String describe() {
        return pattern.segments().size() + " segment cycle anchored " + anchor + " phase " + phase;
    }
}
