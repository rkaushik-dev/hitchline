package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Scored results to be summarised over a window: as one period, per habit or per hitch.
 * Obligations are placed by the day they fell due.
 *
 * @param roster the member's roster
 * @param results scored obligations
 * @param shields the shields that may cover the window
 * @param range the window
 */
public record SummaryRequest(Roster roster, List<ObligationResult> results, List<Shield> shields, DateRange range) {

    public SummaryRequest {
        results = List.copyOf(results);
        shields = List.copyOf(shields);
    }

    private List<ObligationResult> inRange() {
        return results.stream()
                .filter(result -> range.contains(result.obligation().dueOn()))
                .toList();
    }

    private SummaryRequest with(List<ObligationResult> otherResults, DateRange otherRange) {
        return new SummaryRequest(roster, otherResults, shields, otherRange);
    }

    /** The whole window as one period. */
    public PeriodSummary summarise() {
        List<ObligationResult> scoped = inRange();
        StatusTally counts = ObligationResult.countByStatus(scoped);
        return new PeriodSummary(
                range,
                scoped.size(),
                counts.met(),
                counts.partial(),
                counts.missed(),
                counts.pending(),
                Shield.shieldedDayCount(shields, roster.member().id(), range),
                ObligationResult.adherenceRate(scoped));
    }

    /** One summary per habit with something due in the window, in habit id order. */
    public List<HabitSummary> byHabit() {
        List<ObligationResult> scoped = inRange();
        TreeSet<String> ids = new TreeSet<>();
        scoped.forEach(result -> ids.add(result.obligation().habitId()));
        List<HabitSummary> summaries = new ArrayList<>();
        for (String habitId : ids) {
            List<ObligationResult> mine = scoped.stream()
                    .filter(result -> result.obligation().habitId().equals(habitId))
                    .toList();
            summaries.add(HabitSummary.of(habitId, with(mine, range).summarise()));
        }
        return summaries;
    }

    /**
     * One summary per turn of the rotation the window touches, each covering the whole hitch. A
     * crew talks about their last hitch, not their last month, so this is the shape most reports
     * are built on.
     */
    public List<PeriodSummary> byHitch() {
        return roster.member().rotation().hitchesOver(range).stream()
                .map(hitch -> with(results, hitch).summarise())
                .toList();
    }
}
