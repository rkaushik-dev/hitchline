package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitTarget;
import io.github.rkaushikdev.hitchline.domain.schedule.Obligation;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * How one obligation turned out.
 *
 * @param obligation what was owed
 * @param count the check ins that landed inside its window
 * @param total the sum of the readings inside the window, when the habit has a target
 * @param status how it turned out
 * @param progress zero to one, counting both the number of check ins and the target
 */
public record ObligationResult(
        Obligation obligation, int count, OptionalDouble total, AdherenceStatus status, double progress) {

    public ObligationResult {
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(status, "status");
    }

    private static double countProgress(int count, int times) {
        if (times <= 0) {
            return 1;
        }
        return Math.min(1, count / (double) times);
    }

    /**
     * Scores one obligation of {@code habit} against the check ins, as things stood on
     * {@code asOf}. It is met when the count is reached and any target is met; until the window
     * has closed anything short of that is pending, and after it a miss when nothing was done and a
     * partial otherwise.
     */
    public static ObligationResult evaluate(
            Habit habit, Obligation obligation, List<ResolvedCheckIn> resolved, CivilDate asOf) {
        List<ResolvedCheckIn> mine = resolved.stream()
                .filter(item -> item.checkIn().habitId().equals(habit.id()))
                .toList();
        int count = ResolvedCheckIn.countInRange(mine, obligation.window());
        Optional<HabitTarget> target = habit.target();
        OptionalDouble total = target.isEmpty()
                ? OptionalDouble.empty()
                : OptionalDouble.of(
                        target.get().unit().sum(ResolvedCheckIn.readingsInRange(mine, obligation.window())));

        boolean enoughTimes = count >= obligation.times();
        boolean enoughAmount = target.isEmpty() || target.get().isMetBy(total.getAsDouble());
        boolean met = enoughTimes && enoughAmount;
        double progress = target.isEmpty()
                ? countProgress(count, obligation.times())
                : Math.min(
                        countProgress(count, obligation.times()), target.get().progress(total.getAsDouble()));

        boolean closed = asOf.isAfter(obligation.window().to());
        AdherenceStatus status;
        if (met) {
            status = AdherenceStatus.MET;
        } else if (!closed) {
            status = AdherenceStatus.PENDING;
        } else {
            status = count == 0 ? AdherenceStatus.MISSED : AdherenceStatus.PARTIAL;
        }
        return new ObligationResult(obligation, count, total, status, progress);
    }

    /** Scores every obligation, in the order given. */
    public static List<ObligationResult> evaluateAll(
            Habit habit, List<Obligation> obligations, List<ResolvedCheckIn> resolved, CivilDate asOf) {
        return obligations.stream()
                .map(obligation -> evaluate(habit, obligation, resolved, asOf))
                .toList();
    }

    /** True once the result can no longer change: anything but pending. */
    public boolean isSettled() {
        return status != AdherenceStatus.PENDING;
    }

    public static StatusTally countByStatus(List<ObligationResult> results) {
        int met = 0;
        int partial = 0;
        int missed = 0;
        int pending = 0;
        for (ObligationResult result : results) {
            switch (result.status) {
                case MET -> met += 1;
                case PARTIAL -> partial += 1;
                case MISSED -> missed += 1;
                case PENDING -> pending += 1;
            }
        }
        return new StatusTally(met, partial, missed, pending);
    }

    /**
     * Share of the settled obligations that were met. Pending ones are left out of both halves of
     * the fraction rather than counted as failures; with nothing settled the rate is one.
     */
    public static double adherenceRate(List<ObligationResult> results) {
        List<ObligationResult> settled =
                results.stream().filter(ObligationResult::isSettled).toList();
        if (settled.isEmpty()) {
            return 1;
        }
        long met = settled.stream()
                .filter(result -> result.status == AdherenceStatus.MET)
                .count();
        return met / (double) settled.size();
    }

    /** The number of results that can no longer change. */
    public static int settledCount(List<ObligationResult> results) {
        return (int) results.stream().filter(ObligationResult::isSettled).count();
    }
}
