package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A window of days with the role of each one worked out once. Expanding a dozen habits over a year
 * asked the roster the same question a few thousand times, and every one of those walked the
 * override list. The calendar is built once per report and answers from an array.
 *
 * @param window the days covered
 * @param roles the role of each day of the window, in order
 */
public record RoleCalendar(DateRange window, List<DayRole> roles) {

    public RoleCalendar {
        roles = List.copyOf(roles);
    }

    public static RoleCalendar build(Roster roster, DateRange window) {
        List<DayRole> roles = new ArrayList<>();
        for (CivilDate date : window.days()) {
            roles.add(roster.roleFor(date));
        }
        return new RoleCalendar(window, roles);
    }

    /** The role of {@code date} when the calendar covers it. */
    public Optional<DayRole> roleOnDay(CivilDate date) {
        if (!window.contains(date)) {
            return Optional.empty();
        }
        return Optional.of(roles.get(CivilDate.daysBetween(window.from(), date)));
    }

    /** Reads from the calendar when the day is inside it, and asks the roster if not. */
    public DayRole roleThrough(Roster roster, CivilDate date) {
        return roleOnDay(date).orElseGet(() -> roster.roleFor(date));
    }

    /** The runs of same role days inside the calendar, in order. */
    public List<RoleBlock> blocks() {
        List<RoleBlock> blocks = new ArrayList<>();
        for (int offset = 0; offset < roles.size(); offset += 1) {
            DayRole role = roles.get(offset);
            CivilDate date = window.from().plusDays(offset);
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

    public int countIn(DayRole role) {
        return (int) roles.stream().filter(entry -> entry == role).count();
    }

    /** One line, such as {@code 2025-01-06 to 2025-01-12, 7 days}. */
    public String describe() {
        return window.from() + " to " + window.to() + ", " + roles.size() + " days";
    }
}
