package io.github.rkaushikdev.hitchline.domain.schedule;

import io.github.rkaushikdev.hitchline.domain.habit.Cadence;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitHistory;
import io.github.rkaushikdev.hitchline.domain.habit.HabitRevision;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.RoleBlock;
import io.github.rkaushikdev.hitchline.domain.rotation.RoleCalendar;
import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a habit into the list of things it actually asks for over a window. The roster is needed
 * as well as the habit, because a cadence tied to shift days asks for nothing at all during a
 * fortnight at home.
 *
 * @param roster the member's roster, overrides included
 * @param habit the habit to expand
 * @param window the days asked about
 * @param history when given, each stretch of the window is expanded against the revision that was
 *     in force over it, so a cadence changed in March does not rewrite what January asked for
 */
public record Expansion(Roster roster, Habit habit, DateRange window, Optional<HabitHistory> history) {

    public Expansion {
        Objects.requireNonNull(roster, "roster");
        Objects.requireNonNull(habit, "habit");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(history, "history");
    }

    public static Expansion of(Roster roster, Habit habit, DateRange window) {
        return new Expansion(roster, habit, window, Optional.empty());
    }

    public static Expansion of(Roster roster, Habit habit, DateRange window, HabitHistory history) {
        return new Expansion(roster, habit, window, Optional.of(history));
    }

    private Expansion with(Habit otherHabit, DateRange otherWindow) {
        return new Expansion(roster, otherHabit, otherWindow, Optional.empty());
    }

    private Obligation emit(ObligationScope scope, DateRange range, int times) {
        DateRange withGrace = DateRange.of(range.from(), range.to().plusDays(habit.graceDays()));
        return new Obligation(habit.id(), habit.memberId(), scope, withGrace, times);
    }

    private List<Obligation> perDay(int times, List<DayRole> roles) {
        List<Obligation> entries = new ArrayList<>();
        Optional<RoleCalendar> calendar =
                roles.isEmpty() ? Optional.empty() : Optional.of(RoleCalendar.build(roster, window));
        for (CivilDate date : window.days()) {
            if (!habit.isRunningOn(date)) {
                continue;
            }
            if (calendar.isPresent() && !roles.contains(calendar.get().roleThrough(roster, date))) {
                continue;
            }
            entries.add(emit(ObligationScope.DAY, DateRange.singleDay(date), times));
        }
        return entries;
    }

    /**
     * One obligation per turn of the rotation. The window is the whole hitch, so somebody who does
     * the thing three times in their first week has finished what the fortnight asked for.
     */
    private List<Obligation> perHitch(int times) {
        List<Obligation> entries = new ArrayList<>();
        for (DateRange hitch : roster.member().rotation().hitchesOver(window)) {
            habitWindow(habit, hitch).ifPresent(live -> entries.add(emit(ObligationScope.HITCH, live, times)));
        }
        return entries;
    }

    /**
     * One obligation per unbroken run of days in the role. Runs are found over a window widened by
     * a whole cycle at each end, so a block that started before the window still comes back whole.
     */
    private List<Obligation> perBlock(int times, DayRole role) {
        int cycle = roster.member().rotation().pattern().cycleLength();
        DateRange wide =
                DateRange.of(window.from().plusDays(-cycle), window.to().plusDays(cycle));
        List<Obligation> entries = new ArrayList<>();
        for (RoleBlock block : roster.effectiveBlocks(wide)) {
            if (block.role() != role || !block.range().intersects(window)) {
                continue;
            }
            habitWindow(habit, block.range()).ifPresent(live -> entries.add(emit(ObligationScope.BLOCK, live, times)));
        }
        return entries;
    }

    /** One obligation per calendar week, Monday to Sunday, clipped to the habit's life. */
    private List<Obligation> perWeek(int times) {
        List<Obligation> entries = new ArrayList<>();
        CivilDate monday = window.from().startOfIsoWeek();
        while (!monday.isAfter(window.to())) {
            habitWindow(habit, DateRange.of(monday, monday.endOfIsoWeek()))
                    .ifPresent(live -> entries.add(emit(ObligationScope.WEEK, live, times)));
            monday = monday.plusDays(7);
        }
        return entries;
    }

    /**
     * Tiles the habit's life into windows of a fixed length, starting on the day it began. The
     * tiles do not slide: a five in fourteen days habit is measured over the same fortnights every
     * time, so two people can compare readings.
     */
    private List<Obligation> perWindow(int times, int windowDays) {
        CivilDate anchor = habit.startsOn();
        int firstTile = Math.floorDiv(CivilDate.daysBetween(anchor, window.from()), windowDays);
        int lastTile = Math.floorDiv(CivilDate.daysBetween(anchor, window.to()), windowDays);
        List<Obligation> entries = new ArrayList<>();
        for (int tile = firstTile; tile <= lastTile; tile += 1) {
            CivilDate from = anchor.plusDays((long) tile * windowDays);
            habitWindow(habit, DateRange.of(from, from.plusDays(windowDays - 1L)))
                    .ifPresent(live -> entries.add(emit(ObligationScope.WINDOW, live, times)));
        }
        return entries;
    }

    private List<Obligation> expandOne() {
        List<Obligation> entries =
                switch (habit.cadence()) {
                    case Cadence.PerDay day -> perDay(day.times(), List.of());
                    case Cadence.PerRoleDay roleDay -> perDay(roleDay.times(), roleDay.roles());
                    case Cadence.PerHitch hitch -> perHitch(hitch.times());
                    case Cadence.PerWeek week -> perWeek(week.times());
                    case Cadence.PerBlock block -> perBlock(block.times(), block.role());
                    case Cadence.PerWindow tiles -> perWindow(tiles.times(), tiles.windowDays());
                };
        return Obligation.sort(entries);
    }

    /** Everything the habit asks for over the window, in order. */
    public List<Obligation> expand() {
        if (history.isEmpty()) {
            return expandOne();
        }
        HabitHistory revisions = history.get();
        Map<String, Obligation> seen = new LinkedHashMap<>();
        for (DateRange segment : revisionSegments(revisions, window)) {
            Habit terms = revisions.asOf(habit, segment.from());
            for (Obligation entry : with(terms, segment).expandOne()) {
                // A hitch that straddles a revision comes back from both stretches. The later
                // revision wins, because that is the one in force when it fell due.
                seen.put(entry.key(), entry);
            }
        }
        return Obligation.sort(new ArrayList<>(seen.values()));
    }

    /**
     * The stretches of a window each revision covers, clipped to the window. A habit with no
     * history behaves as one stretch, which is what every caller that predates revisions gets.
     */
    public static List<DateRange> revisionSegments(HabitHistory history, DateRange window) {
        List<DateRange> segments = new ArrayList<>();
        List<HabitRevision> revisions = history.revisions();
        for (int index = 0; index < revisions.size(); index += 1) {
            CivilDate from = revisions.get(index).from();
            CivilDate to = index + 1 < revisions.size()
                    ? revisions.get(index + 1).from().plusDays(-1)
                    : window.to();
            if (to.isBefore(from)) {
                continue;
            }
            DateRange.of(from, to).clamp(window).ifPresent(segments::add);
        }
        return segments;
    }

    /**
     * Expands several habits over the same window. Every habit has to belong to the person the
     * roster is for, which the caller is expected to have sorted out already.
     */
    public static List<Obligation> expandAll(Roster roster, List<Habit> habits, DateRange window) {
        List<Obligation> entries = new ArrayList<>();
        for (Habit entry : habits) {
            entries.addAll(of(roster, entry, window).expand());
        }
        return Obligation.sort(entries);
    }

    /** Narrows a window to the part of it the habit is alive for. */
    public static Optional<DateRange> habitWindow(Habit entry, DateRange window) {
        CivilDate end = entry.endsOn().orElse(window.to());
        if (end.isBefore(entry.startsOn())) {
            return Optional.empty();
        }
        return window.clamp(DateRange.of(entry.startsOn(), end));
    }
}
