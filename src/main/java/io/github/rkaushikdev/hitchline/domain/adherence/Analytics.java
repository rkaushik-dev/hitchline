package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.time.ClockTime;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The patterns a crew argues about. None of this changes what anybody is measured against; it is
 * here because "you only ever do it on the way to breakfast" is a more useful thing to tell
 * somebody than a percentage.
 */
public final class Analytics {

    /** How far the latest rate must move from the average before it counts as a change. */
    public static final double DEFAULT_TOLERANCE = 0.05;

    private Analytics() {}

    /** Counts check ins by the local hour they happened in, on the day's own clock: 24 buckets. */
    public static List<HourBucket> byHourOfDay(List<ResolvedCheckIn> resolved) {
        int[] counts = new int[24];
        for (ResolvedCheckIn item : resolved) {
            ClockTime local =
                    Zones.clockTimeIn(item.day().zone(), item.checkIn().at());
            counts[local.minutesSinceMidnight() / 60] += 1;
        }
        List<HourBucket> buckets = new ArrayList<>();
        for (int hour = 0; hour < counts.length; hour += 1) {
            buckets.add(new HourBucket(hour, counts[hour]));
        }
        return buckets;
    }

    /** The hour with the most check ins, the earliest of a tie, or nothing when none were made. */
    public static Optional<HourBucket> busiestHour(List<ResolvedCheckIn> resolved) {
        HourBucket best = null;
        for (HourBucket bucket : byHourOfDay(resolved)) {
            if (bucket.count() > 0 && (best == null || bucket.count() > best.count())) {
                best = bucket;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * How the same person does on shift against at home, one split per role that anything fell
     * on, in the usual role order. This is the reading the whole tool exists for: a habit that
     * holds up offshore and collapses ashore is a different problem from one nobody ever does.
     */
    public static List<RoleSplit> byRole(List<ObligationResult> results, Function<ObligationResult, DayRole> roleOf) {
        List<RoleSplit> splits = new ArrayList<>();
        for (DayRole role : DayRole.ALL) {
            List<ObligationResult> mine = results.stream()
                    .filter(result -> roleOf.apply(result) == role)
                    .toList();
            if (!mine.isEmpty()) {
                splits.add(RoleSplit.of(role, StatusTally.of(mine)));
            }
        }
        return splits;
    }

    /**
     * How many hitches in a row, counting back from the latest, were cleared with nothing missed
     * and nothing done in part. A hitch that owed nothing neither counts nor breaks the run, and
     * neither does the latest one while something in it is still open and nothing has gone wrong,
     * because it has not finished yet. Crews talk about clean hitches, not clean days.
     */
    public static int cleanHitchesInARow(List<PeriodSummary> byHitch) {
        int run = 0;
        boolean latest = true;
        for (int index = byHitch.size() - 1; index >= 0; index -= 1) {
            PeriodSummary hitch = byHitch.get(index);
            if (hitch.owed() == 0) {
                continue;
            }
            if (!hitch.isClean()) {
                return run;
            }
            if (hitch.pending() > 0) {
                if (!latest) {
                    return run;
                }
            } else {
                run += 1;
            }
            latest = false;
        }
        return run;
    }

    public static Trend trendOf(List<PeriodSummary> summaries) {
        return trendOf(summaries, DEFAULT_TOLERANCE);
    }

    /**
     * Compares the last window that owed anything with the average of the ones before it. Two
     * readings are not a trend, so anything shorter than three answers unknown rather than
     * inventing a direction.
     */
    public static Trend trendOf(List<PeriodSummary> summaries, double tolerance) {
        List<PeriodSummary> scored =
                summaries.stream().filter(summary -> summary.owed() > 0).toList();
        if (scored.size() < 3) {
            return Trend.UNKNOWN;
        }
        PeriodSummary last = scored.get(scored.size() - 1);
        List<PeriodSummary> earlier = scored.subList(0, scored.size() - 1);
        double total = 0;
        for (PeriodSummary summary : earlier) {
            total += summary.rate();
        }
        double average = total / earlier.size();
        if (last.rate() > average + tolerance) {
            return Trend.RISING;
        }
        if (last.rate() < average - tolerance) {
            return Trend.FALLING;
        }
        return Trend.STEADY;
    }
}
