package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.ArrayList;
import java.util.List;

/**
 * An unbroken run of days sharing one role.
 *
 * @param role the role of every day in the run
 * @param range the days of the run
 */
public record RoleBlock(DayRole role, DateRange range) {

    /**
     * Joins the roles of consecutive days into runs. {@code roles} holds one role per day starting
     * on {@code from}; neighbouring days of one role become one block, so the blocks come back in
     * order, never touch a block of their own role, and cover every day given.
     */
    public static List<RoleBlock> runsOf(CivilDate from, List<DayRole> roles) {
        List<RoleBlock> blocks = new ArrayList<>();
        int start = 0;
        for (int offset = 1; offset <= roles.size(); offset += 1) {
            if (offset == roles.size() || roles.get(offset) != roles.get(start)) {
                blocks.add(new RoleBlock(
                        roles.get(start), DateRange.of(from.plusDays(start), from.plusDays(offset - 1L))));
                start = offset;
            }
        }
        return blocks;
    }

    /** How many days the block runs for. */
    public int days() {
        return range.length();
    }

    /** The block as {@code role from..to}, which is how reports list them. */
    public String describe() {
        return role + " " + range;
    }
}
