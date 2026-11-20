package io.github.rkaushikdev.hitchline.domain.crew;

import io.github.rkaushikdev.hitchline.domain.ForbiddenException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Who may read somebody else's adherence. The default is deliberately closed: a habit tracker that
 * quietly shows a supervisor how much sleep somebody is logging is a surveillance tool, and crews
 * stop using those.
 *
 * @param memberId whose adherence it guards
 * @param fallback the setting for every habit without one of its own
 * @param perHabit per habit settings, which win over the fallback, in the order they were made
 */
public record SharingPreference(String memberId, Visibility fallback, Map<String, Visibility> perHabit) {

    public SharingPreference {
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(fallback, "fallback");
        perHabit = Collections.unmodifiableMap(new LinkedHashMap<>(perHabit));
    }

    /** A closed preference: every habit private. */
    public static SharingPreference of(String memberId) {
        return new SharingPreference(memberId, Visibility.PRIVATE, Map.of());
    }

    public static SharingPreference of(String memberId, Visibility fallback) {
        return new SharingPreference(memberId, fallback, Map.of());
    }

    /** A preference whose fallback is written as a word, refused when it is not a visibility. */
    public static SharingPreference of(String memberId, String fallback) {
        Visibility visibility = Visibility.fromWire(fallback)
                .orElseThrow(() -> ValidationException.field("fallback", "is not a visibility: " + fallback));
        return of(memberId, visibility);
    }

    public Visibility visibilityFor(String habitId) {
        return perHabit.getOrDefault(habitId, fallback);
    }

    public SharingPreference withHabitVisibility(String habitId, Visibility visibility) {
        Map<String, Visibility> next = new LinkedHashMap<>(perHabit);
        next.put(habitId, visibility);
        return new SharingPreference(memberId, fallback, next);
    }

    public SharingPreference withoutHabitVisibility(String habitId) {
        Map<String, Visibility> next = new LinkedHashMap<>(perHabit);
        next.remove(habitId);
        return new SharingPreference(memberId, fallback, next);
    }

    /**
     * True when the viewer may read that habit's adherence for that person. Anybody may read their
     * own. Otherwise both must be in the same crew on the day asked about, and the habit's setting
     * decides: never when private, always when crew, and only for a lead or coordinator when lead
     * only.
     */
    public static boolean canRead(AccessRequest request) {
        Viewer viewer = request.viewer();
        SharingPreference preference = request.preference();
        if (viewer.memberId().equals(preference.memberId())) {
            return true;
        }
        Optional<Membership> theirs = Membership.crewOf(request.memberships(), preference.memberId(), request.on());
        Optional<Membership> mine = Membership.crewOf(request.memberships(), viewer.memberId(), request.on());
        if (theirs.isEmpty() || mine.isEmpty()) {
            return false;
        }
        if (!theirs.get().crewId().equals(mine.get().crewId())) {
            return false;
        }
        return switch (preference.visibilityFor(request.habitId())) {
            case PRIVATE -> false;
            case CREW -> true;
            case LEAD_ONLY -> mine.get().role() != CrewRole.MEMBER;
        };
    }

    /** Passes quietly when {@link #canRead} allows the request, and refuses it otherwise. */
    public static void requireRead(AccessRequest request) {
        if (!canRead(request)) {
            throw new ForbiddenException(request.viewer().memberId() + " may not read " + request.habitId() + " for "
                    + request.preference().memberId());
        }
    }

    /** The habits of one person a viewer is allowed to see, in the order given. */
    public static List<String> readableHabits(
            Viewer viewer,
            SharingPreference preference,
            List<Membership> memberships,
            CivilDate on,
            List<String> habitIds) {
        return habitIds.stream()
                .filter(habitId -> canRead(new AccessRequest(viewer, preference, habitId, memberships, on)))
                .toList();
    }
}
