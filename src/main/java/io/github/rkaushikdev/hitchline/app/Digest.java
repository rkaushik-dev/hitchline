package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.adherence.PeriodSummary;
import io.github.rkaushikdev.hitchline.domain.adherence.StatusTally;
import io.github.rkaushikdev.hitchline.domain.adherence.StreakSummary;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.rotation.DutyLimits;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationAssignment;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.util.Collation;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The end of hitch note. A crew member coming ashore wants four lines about how it went, not a
 * dashboard, and the same four lines are what a lead reads over somebody's shoulder. It is plain
 * text because that survives every handset, mail client and printout on a rig.
 *
 * @param member who it is for
 * @param summary the hitch summarised
 * @param habits their habits
 * @param results every obligation of the hitch scored
 * @param streaks each habit's run, by habit id
 */
public record Digest(
        CrewMember member,
        PeriodSummary summary,
        List<Habit> habits,
        List<ObligationResult> results,
        Map<String, StreakSummary> streaks) {

    public Digest {
        habits = List.copyOf(habits);
        results = List.copyOf(results);
        streaks = Collections.unmodifiableMap(new LinkedHashMap<>(streaks));
    }

    /**
     * One habit's line.
     *
     * @param name the habit
     * @param owed obligations that are settled
     * @param met how many of those were met
     * @param partial how many of those were done only in part
     * @param streak the run still going
     */
    public record HabitLine(String name, int owed, int met, int partial, int streak) {

        /** The line as the note prints it, such as {@code - Call home: 1 of 2, 3 in a row}. */
        public String describe() {
            String part = partial == 0 ? "" : ", " + partial + " part done";
            String run = streak == 0 ? "" : ", " + streak + " in a row";
            return "- " + name + ": " + share(met, owed) + part + run;
        }
    }

    private static final Comparator<HabitLine> MOST_MET_FIRST =
            Comparator.comparingInt(HabitLine::met).reversed().thenComparing(HabitLine::name, Collation.ORDER);

    /** A line for every habit that owed anything, the most met first, then by name. */
    public List<HabitLine> habitLines() {
        return habits.stream()
                .map(habit -> lineFor(
                        habit,
                        StatusTally.of(results.stream()
                                .filter(result -> result.obligation().habitId().equals(habit.id()))
                                .toList())))
                .filter(line -> line.owed() > 0)
                .sorted(MOST_MET_FIRST)
                .toList();
    }

    private HabitLine lineFor(Habit habit, StatusTally tally) {
        int streak = Optional.ofNullable(streaks.get(habit.id()))
                .map(StreakSummary::current)
                .orElse(0);
        return new HabitLine(habit.name(), tally.settled(), tally.met(), tally.partial(), streak);
    }

    private static String share(int met, int owed) {
        return owed == 0 ? "nothing was owed" : met + " of " + owed;
    }

    /** The opening line, which is the only line most people read. */
    public String headline() {
        String opening = member.displayName() + ", ";
        String window = " over " + summary.range() + ".";
        if (summary.owed() == 0) {
            return opening + "nothing was owed" + window;
        }
        if (summary.isClean()) {
            return opening + "you cleared everything" + window;
        }
        return opening + "you did " + share(summary.met(), summary.owed() - summary.pending()) + window;
    }

    /**
     * The first day of the member's next trip after the note's window, read from the pattern: the
     * line somebody going ashore most wants to see at the bottom.
     */
    public Optional<CivilDate> nextTripStarts() {
        RotationAssignment rotation = member.rotation();
        CivilDate last = summary.range().to();
        for (int step = 1; step <= rotation.pattern().cycleLength() * 2; step += 1) {
            CivilDate day = last.plusDays(step);
            if (DutyLimits.isAway(rotation.roleOn(day)) && !DutyLimits.isAway(rotation.roleOn(day.plusDays(-1)))) {
                return Optional.of(day);
            }
        }
        return Optional.empty();
    }

    /** The whole note, ending in one newline. */
    public String text() {
        List<String> lines = new ArrayList<>(List.of(headline(), ""));
        List<HabitLine> habitLines = habitLines();
        habitLines.stream().map(HabitLine::describe).forEach(lines::add);
        if (summary.shieldedDays() > 0) {
            lines.addAll(List.of("", summary.shieldedDays() + " days were shielded and are not held against you."));
        }
        habitLines.stream()
                .max(Comparator.comparingInt(HabitLine::streak).thenComparing(MOST_MET_FIRST.reversed()))
                .filter(best -> best.streak() > 2)
                .ifPresent(best -> lines.addAll(
                        List.of("", "Longest run this hitch: " + best.name() + " at " + best.streak() + ".")));
        nextTripStarts().ifPresent(day -> lines.addAll(List.of("", "Next trip starts " + day + ".")));
        return Texts.trimEnd(String.join("\n", lines)) + "\n";
    }

    public String subjectLine() {
        return "hitchline: " + summary.range() + " for " + member.displayName();
    }
}
