package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import java.util.List;
import java.util.Optional;

/** Habits, by id and by member. Saving a habit with an id already held replaces it. */
public interface HabitRepository {

    Optional<Habit> byId(String id);

    List<Habit> byMember(String memberId);

    void save(Habit habit);
}
