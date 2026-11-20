package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.List;
import java.util.Optional;

/**
 * Check ins, which are added and replaced but never removed. Lists come back in the order the
 * entries were first added.
 */
public interface CheckInRepository {

    Optional<CheckIn> byId(String id);

    /** Everything the member checked in. */
    List<CheckIn> byMember(String memberId);

    /**
     * The member's check ins whose event time falls on a day of {@code window} as read on their
     * site clock. It is a coarse filter: a caller that needs the rotation day resolves each entry
     * through the zoning rules once the rows are loaded.
     */
    List<CheckIn> byMember(String memberId, DateRange window);

    List<CheckIn> byHabit(String habitId);

    /** Stores a new entry; an id already stored is a conflict. */
    void add(CheckIn entry);

    /** Replaces a stored entry; an id not stored is not found. */
    void replace(CheckIn entry);
}
