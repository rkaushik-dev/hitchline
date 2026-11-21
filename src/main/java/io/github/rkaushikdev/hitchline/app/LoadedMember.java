package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.adherence.Shield;
import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import java.util.List;

/**
 * One member, loaded whole: the roster with its overrides, the habits, the live check ins placed
 * on their rotation days, and the shields.
 *
 * @param roster the member and every override stored for them
 * @param habits the member's habits
 * @param resolved the check ins that were not voided
 * @param shields the shields stored for the member
 * @param asOf the date it is where the person is working, used to settle obligations
 */
public record LoadedMember(
        Roster roster, List<Habit> habits, List<ResolvedCheckIn> resolved, List<Shield> shields, CivilDate asOf) {

    public LoadedMember {
        habits = List.copyOf(habits);
        resolved = List.copyOf(resolved);
        shields = List.copyOf(shields);
    }

    /** The member's habit with {@code habitId}, or a not found failure. */
    public Habit habitOf(String habitId) {
        return habits.stream()
                .filter(habit -> habit.id().equals(habitId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("habit", habitId));
    }
}
