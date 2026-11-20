package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A streak counted in obligations rather than in days. A person on a fortnight rotation with a
 * shift day habit owes nothing at home, so their streak has to survive the fortnight without
 * pretending they did anything during it.
 *
 * @param current the run still going
 * @param longest the longest run seen
 * @param lastMetOn the day the last met obligation fell due
 * @param shielded obligations skipped because the day they fell due on was shielded
 * @param brokenOn the most recent obligation that broke a run, if any did
 */
public record StreakSummary(
        int current, int longest, Optional<CivilDate> lastMetOn, int shielded, Optional<CivilDate> brokenOn) {

    /** No run at all. */
    public static final StreakSummary NO_STREAK = new StreakSummary(0, 0, Optional.empty(), 0, Optional.empty());

    static final Comparator<ObligationResult> BY_DUE =
            Comparator.comparing(result -> result.obligation().dueOn());

    static List<ObligationResult> settledInOrder(List<ObligationResult> results) {
        return results.stream()
                .filter(ObligationResult::isSettled)
                .sorted(BY_DUE)
                .toList();
    }

    /**
     * Walks the settled results in the order they fell due. A met obligation extends the run, a
     * partial or a miss breaks it, and one that fell due on a shielded day of the member is
     * stepped over without doing either.
     */
    public static StreakSummary compute(List<ObligationResult> results, List<Shield> shields, String memberId) {
        int current = 0;
        int longest = 0;
        int shielded = 0;
        CivilDate lastMetOn = null;
        CivilDate brokenOn = null;
        for (ObligationResult result : settledInOrder(results)) {
            CivilDate due = result.obligation().dueOn();
            if (Shield.isShielded(shields, memberId, due)) {
                shielded += 1;
                continue;
            }
            if (result.status() == AdherenceStatus.MET) {
                current += 1;
                longest = Math.max(longest, current);
                lastMetOn = due;
                continue;
            }
            current = 0;
            brokenOn = due;
        }
        return new StreakSummary(
                current, longest, Optional.ofNullable(lastMetOn), shielded, Optional.ofNullable(brokenOn));
    }

    /**
     * True when a run is alive and something due has not been started yet. Used to decide whether a
     * nudge is worth sending at all.
     */
    public boolean isAtRisk(List<ObligationResult> results) {
        if (current == 0) {
            return false;
        }
        return results.stream().anyMatch(result -> result.status() == AdherenceStatus.PENDING && result.count() == 0);
    }

    public boolean isBroken() {
        return brokenOn.isPresent() && current == 0;
    }

    /** The best run ever recorded, whether or not it is the one still running. */
    public int bestRun() {
        return Math.max(current, longest);
    }

    /** One line, such as {@code 2 in a row, best 2}. */
    public String describe() {
        if (current == 0) {
            return longest == 0 ? "no run yet" : "run broken, best was " + longest;
        }
        return current + " in a row, best " + bestRun();
    }
}
