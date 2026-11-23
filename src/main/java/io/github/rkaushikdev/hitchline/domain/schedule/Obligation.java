package io.github.rkaushikdev.hitchline.domain.schedule;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Something a habit asks for over a stretch of days. It is reported against the last day of its
 * window, which is when it falls due.
 *
 * @param habitId the habit asking
 * @param memberId whose habit it is
 * @param scope what the cadence was measured over
 * @param window the days a check in may land on to count towards it
 * @param times how many check ins it asks for, at least one
 */
public record Obligation(String habitId, String memberId, ObligationScope scope, DateRange window, int times) {

    /** By window, then by habit. */
    public static final Comparator<Obligation> ORDER =
            Comparator.comparing(Obligation::window).thenComparing(Obligation::habitId);

    public Obligation {
        Objects.requireNonNull(habitId, "habitId");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(window, "window");
        if (times < 1) {
            throw ValidationException.field("times", "must be a whole number above zero, got " + times);
        }
    }

    /** The same obligation asking for {@code count} check ins instead. */
    public Obligation withTimes(int count) {
        return new Obligation(habitId, memberId, scope, window, count);
    }

    /** The day it is reported against, always the last day of the window. */
    public CivilDate dueOn() {
        return window.to();
    }

    /**
     * Stable identity for an obligation. The scope is part of it because the same habit can be
     * rescheduled onto a different scope, and the two runs must not be mistaken for one another
     * when a report is rebuilt.
     */
    public String key() {
        return habitId + ":" + scope + ":" + dueOn();
    }

    public boolean acceptsCheckInOn(CivilDate date) {
        return window.contains(date);
    }

    /** How wide the window is, which is how much room there was to get it done. */
    public int span() {
        return window.length();
    }

    public boolean isOverdueOn(CivilDate today) {
        return !today.isWithin(window.from(), window.to());
    }

    /** One line, such as {@code 3 times per hitch over 2025-01-06..2025-02-02}. */
    public String describe() {
        String count = times == 1 ? "once" : times + " times";
        return count + " per " + scope + " over " + window;
    }

    /** The obligations ordered by window, then by habit. */
    public static List<Obligation> sort(List<Obligation> entries) {
        List<Obligation> sorted = new ArrayList<>(entries);
        sorted.sort(ORDER);
        return sorted;
    }

    public static List<Obligation> forHabit(List<Obligation> entries, String habitId) {
        return sort(
                entries.stream().filter(entry -> entry.habitId.equals(habitId)).toList());
    }

    public static int totalTimes(List<Obligation> entries) {
        return entries.stream().mapToInt(Obligation::times).sum();
    }
}
