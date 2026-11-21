package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.adherence.PeriodSummary;
import io.github.rkaushikdev.hitchline.domain.adherence.StreakSummary;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
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
     * @param streak the run still going
     */
    public record HabitLine(String name, int owed, int met, int streak) {}

    /** A line for every habit that owed anything, the most met first, then by name. */
    public List<HabitLine> habitLines() {
        List<HabitLine> lines = new ArrayList<>();
        for (Habit habit : habits) {
            List<ObligationResult> mine = results.stream()
                    .filter(result -> result.obligation().habitId().equals(habit.id()))
                    .toList();
            int owed = (int) mine.stream()
                    .filter(result -> result.status() != AdherenceStatus.PENDING)
                    .count();
            int met = (int) mine.stream()
                    .filter(result -> result.status() == AdherenceStatus.MET)
                    .count();
            int streak = Optional.ofNullable(streaks.get(habit.id()))
                    .map(StreakSummary::current)
                    .orElse(0);
            if (owed > 0) {
                lines.add(new HabitLine(habit.name(), owed, met, streak));
            }
        }
        lines.sort(Comparator.comparingInt(HabitLine::met).reversed().thenComparing(HabitLine::name, Collation.ORDER));
        return lines;
    }

    private static String share(int met, int owed) {
        if (owed == 0) {
            return "nothing was owed";
        }
        return met + " of " + owed;
    }

    /** The opening line, which is the only line most people read. */
    public String headline() {
        String window = summary.range().toString();
        String name = member.displayName();
        if (summary.owed() == 0) {
            return name + ", nothing was owed over " + window + ".";
        }
        if (summary.missed() == 0 && summary.partial() == 0) {
            return name + ", you cleared everything over " + window + ".";
        }
        return name + ", you did " + share(summary.met(), summary.owed() - summary.pending()) + " over " + window + ".";
    }

    /** The whole note, ending in one newline. */
    public String text() {
        List<String> lines = new ArrayList<>(List.of(headline(), ""));
        List<HabitLine> habitLines = habitLines();
        for (HabitLine line : habitLines) {
            String run = line.streak() == 0 ? "" : ", " + line.streak() + " in a row";
            lines.add("- " + line.name() + ": " + share(line.met(), line.owed()) + run);
        }
        if (summary.shieldedDays() > 0) {
            lines.add("");
            lines.add(summary.shieldedDays() + " days were shielded and are not held against you.");
        }
        HabitLine best = null;
        for (HabitLine line : habitLines) {
            if (best == null || line.streak() > best.streak()) {
                best = line;
            }
        }
        if (best != null && best.streak() > 2) {
            lines.add("");
            lines.add("Longest run this hitch: " + best.name() + " at " + best.streak() + ".");
        }
        return Texts.trimEnd(String.join("\n", lines)) + "\n";
    }

    public String subjectLine() {
        return "hitchline: " + summary.range() + " for " + member.displayName();
    }
}
