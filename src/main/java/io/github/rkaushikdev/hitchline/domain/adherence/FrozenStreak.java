package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A streak that may carry a few misses, spending freezes from a budget.
 *
 * @param current the run still going
 * @param longest the longest run seen
 * @param lastMetOn the day the last met obligation fell due
 * @param shielded obligations skipped because their day was shielded
 * @param brokenOn the most recent obligation that broke a run
 * @param frozen misses the run absorbed rather than breaking on
 * @param freezesLeft freezes still in hand on the last day scored
 */
public record FrozenStreak(
        int current,
        int longest,
        Optional<CivilDate> lastMetOn,
        int shielded,
        Optional<CivilDate> brokenOn,
        int frozen,
        int freezesLeft) {

    /** Walks the run with the default budget of one miss in 28 days. */
    public static FrozenStreak compute(List<ObligationResult> results, List<Shield> shields, String memberId) {
        return compute(results, shields, memberId, FreezeBudget.DEFAULT);
    }

    /**
     * Walks the run the way {@link StreakSummary#compute} does, but lets a miss be absorbed while
     * the budget holds. The first thing in a run is never frozen. Freezes spent outside the window
     * come back, so a person who slipped once in March is not still paying for it in June.
     */
    public static FrozenStreak compute(
            List<ObligationResult> results, List<Shield> shields, String memberId, FreezeBudget budget) {
        List<ObligationResult> settled = StreakSummary.settledInOrder(results);
        int current = 0;
        int longest = 0;
        int shielded = 0;
        int frozen = 0;
        CivilDate lastMetOn = null;
        CivilDate brokenOn = null;
        List<CivilDate> spent = new ArrayList<>();

        for (ObligationResult result : settled) {
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
            List<CivilDate> live = new ArrayList<>(spent.stream()
                    .filter(day -> CivilDate.daysBetween(day, due) < budget.windowDays())
                    .toList());
            if (current > 0 && live.size() < budget.allowance()) {
                live.add(due);
                spent = live;
                frozen += 1;
                continue;
            }
            current = 0;
            brokenOn = due;
        }

        long stillLive;
        if (settled.isEmpty()) {
            stillLive = spent.size();
        } else {
            CivilDate last = settled.get(settled.size() - 1).obligation().dueOn();
            stillLive = spent.stream()
                    .filter(day -> CivilDate.daysBetween(day, last) < budget.windowDays())
                    .count();
        }
        return new FrozenStreak(
                current, longest, Optional.ofNullable(lastMetOn), shielded, Optional.ofNullable(brokenOn), frozen, (int)
                        Math.max(0, budget.allowance() - stillLive));
    }

    public FrozenStreak withLastMetOn(Optional<CivilDate> value) {
        return new FrozenStreak(current, longest, value, shielded, brokenOn, frozen, freezesLeft);
    }

    /** One line, such as {@code one day carried, 0 freezes in hand}. */
    public String describe() {
        if (frozen == 0) {
            return freezesLeft + " freezes in hand";
        }
        String used = frozen == 1 ? "one day" : frozen + " days";
        return used + " carried, " + freezesLeft + " freezes in hand";
    }

    /** The day something was last met, or {@code never}. */
    public String lastMetLabel() {
        return lastMetOn.map(CivilDate::toString).orElse("never");
    }
}
