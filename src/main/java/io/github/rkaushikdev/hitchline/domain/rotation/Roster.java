package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A member's pattern plus whatever the roster clerk has done to it. Every question about what a
 * day means goes through here rather than through the assignment, because the assignment does not
 * know about held over hitches.
 *
 * @param member whose roster it is
 * @param overrides the entries the clerk made
 */
public record Roster(CrewMember member, List<RosterOverride> overrides) {

    public Roster {
        overrides = List.copyOf(overrides);
    }

    public static Roster of(CrewMember member) {
        return new Roster(member, List.of());
    }

    public static Roster of(CrewMember member, List<RosterOverride> overrides) {
        return new Roster(member, overrides);
    }

    public EffectiveRole effectiveRoleOn(CivilDate date) {
        Optional<RosterOverride> entry = RosterOverride.overrideOn(overrides, member.id(), date);
        if (entry.isPresent()) {
            return new EffectiveRole(entry.get().role(), RoleSource.OVERRIDE);
        }
        return new EffectiveRole(member.rotation().roleOn(date), RoleSource.PATTERN);
    }

    public DayRole roleFor(CivilDate date) {
        return effectiveRoleOn(date).role();
    }

    public boolean isOnShift(CivilDate date) {
        return roleFor(date) == DayRole.ONSHIFT;
    }

    public List<DayRole> rolesFor(DateRange range) {
        return range.days().stream().map(this::roleFor).toList();
    }

    /**
     * The runs of same role days over a window, with overrides folded in. Adjacent runs of the same
     * role are joined, so a hitch held over by three days reads as one block of seventeen rather
     * than two blocks that happen to touch.
     */
    public List<RoleBlock> effectiveBlocks(DateRange range) {
        if (RosterOverride.overridesOver(overrides, member.id(), range).isEmpty()) {
            return member.rotation().blocksOver(range);
        }
        List<RoleBlock> blocks = new ArrayList<>();
        for (CivilDate date : range.days()) {
            DayRole role = roleFor(date);
            if (!blocks.isEmpty()) {
                RoleBlock last = blocks.get(blocks.size() - 1);
                if (last.role() == role) {
                    blocks.set(
                            blocks.size() - 1,
                            new RoleBlock(role, DateRange.of(last.range().from(), date)));
                    continue;
                }
            }
            blocks.add(new RoleBlock(role, DateRange.singleDay(date)));
        }
        return blocks;
    }

    public int countRoleFor(DateRange range, DayRole role) {
        return (int) rolesFor(range).stream().filter(entry -> entry == role).count();
    }

    /** How much of a window the person is expected to be at work for. */
    public int dutyDays(DateRange range) {
        return countRoleFor(range, DayRole.ONSHIFT);
    }

    public boolean isFullyCovered(DateRange range) {
        return rolesFor(range).size() == range.length();
    }
}
