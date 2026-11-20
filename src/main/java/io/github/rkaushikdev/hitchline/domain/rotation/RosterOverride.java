package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A run of days the roster clerk changed for one person, with the reason why. The note is trimmed
 * on the way in; an entry may cover at most 120 days.
 *
 * @param memberId whose roster it changes
 * @param range the days it covers
 * @param role what those days are instead
 * @param reason why
 * @param note anything the clerk wrote, at most 240 characters
 * @param enteredAt when it was entered
 */
public record RosterOverride(
        String memberId, DateRange range, DayRole role, OverrideReason reason, String note, Instant enteredAt) {

    static final int MAX_OVERRIDE_DAYS = 120;
    static final int NOTE_MAX = 240;

    private static final Comparator<RosterOverride> BY_RANGE = Comparator.comparing(RosterOverride::range);

    public RosterOverride {
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(enteredAt, "enteredAt");
        int days = range.length();
        if (days > MAX_OVERRIDE_DAYS) {
            throw ValidationException.field(
                    "range", "covers " + days + " days, more than the " + MAX_OVERRIDE_DAYS + " allowed in one entry");
        }
        note = Texts.trim(note == null ? "" : note);
        if (note.length() > NOTE_MAX) {
            throw ValidationException.field("note", "must be at most " + NOTE_MAX + " characters");
        }
    }

    public static RosterOverride of(
            String memberId, DateRange range, DayRole role, OverrideReason reason, Instant enteredAt) {
        return new RosterOverride(memberId, range, role, reason, "", enteredAt);
    }

    /** The entries ordered by the day they start, then by the day they end. */
    public static List<RosterOverride> sort(List<RosterOverride> overrides) {
        List<RosterOverride> sorted = new ArrayList<>(overrides);
        sorted.sort(BY_RANGE);
        return sorted;
    }

    /**
     * Two entries for the same person may not cover the same day. Somebody has to decide which one
     * is right, and it is not this module.
     */
    public static void checkNoClashes(List<RosterOverride> overrides) {
        List<RosterOverride> sorted = sort(overrides);
        for (int index = 1; index < sorted.size(); index += 1) {
            RosterOverride previous = sorted.get(index - 1);
            RosterOverride current = sorted.get(index);
            if (!previous.memberId.equals(current.memberId)) {
                continue;
            }
            if (previous.range.intersects(current.range)) {
                throw new ConflictException(
                        "roster entries " + previous.range + " and " + current.range + " cover the same days");
            }
        }
    }

    /** The entry covering {@code date} for {@code memberId}, the first one listed if several do. */
    public static Optional<RosterOverride> overrideOn(List<RosterOverride> overrides, String memberId, CivilDate date) {
        return overrides.stream()
                .filter(entry -> entry.memberId.equals(memberId) && entry.range.contains(date))
                .findFirst();
    }

    /** Every entry for {@code memberId} that touches {@code range}, in order. */
    public static List<RosterOverride> overridesOver(List<RosterOverride> overrides, String memberId, DateRange range) {
        return sort(overrides.stream()
                .filter(entry -> entry.memberId.equals(memberId) && entry.range.intersects(range))
                .toList());
    }

    /** One line for the roster, such as {@code 2025-01-20..2025-01-22 onshift because held_over}. */
    public String describe() {
        String written = note.isEmpty() ? "" : " (" + note + ")";
        return range + " " + role + " because " + reason + written;
    }

    public boolean coversDay(CivilDate date) {
        return range.contains(date);
    }

    public String firstDay() {
        return range.from().toString();
    }
}
