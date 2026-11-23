package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus;
import io.github.rkaushikdev.hitchline.domain.adherence.Analytics;
import io.github.rkaushikdev.hitchline.domain.adherence.HabitSummary;
import io.github.rkaushikdev.hitchline.domain.adherence.HourBucket;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.adherence.PeriodSummary;
import io.github.rkaushikdev.hitchline.domain.adherence.RestBreach;
import io.github.rkaushikdev.hitchline.domain.adherence.RestHoursRule;
import io.github.rkaushikdev.hitchline.domain.adherence.RoleSplit;
import io.github.rkaushikdev.hitchline.domain.adherence.Shield;
import io.github.rkaushikdev.hitchline.domain.adherence.StreakSummary;
import io.github.rkaushikdev.hitchline.domain.adherence.SummaryRequest;
import io.github.rkaushikdev.hitchline.domain.adherence.Trend;
import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.TargetUnit;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.List;
import java.util.OptionalInt;

/**
 * The read side: turning a loaded member into the shapes a report wants. Loading the member is
 * the loader's job.
 */
public final class AdherenceService {

    private final MemberLoader loader;

    public AdherenceService(Ports ports) {
        this.loader = new MemberLoader(ports);
    }

    /**
     * Everything a report shows for a window.
     *
     * @param window the days reported
     * @param asOf the date it is where the person is working
     * @param results every obligation scored
     * @param summary the window as one period
     * @param byHabit the window per habit
     * @param byHitch one period per hitch the window touches
     * @param byRole how the same person does on shift against at home
     * @param trend which way the last hitch moved
     * @param busiestHour the local hour most check ins happened in, when there were any
     */
    public record AdherenceReport(
            DateRange window,
            CivilDate asOf,
            List<ObligationResult> results,
            PeriodSummary summary,
            List<HabitSummary> byHabit,
            List<PeriodSummary> byHitch,
            List<RoleSplit> byRole,
            Trend trend,
            OptionalInt busiestHour) {

        public AdherenceReport {
            results = List.copyOf(results);
            byHabit = List.copyOf(byHabit);
            byHitch = List.copyOf(byHitch);
            byRole = List.copyOf(byRole);
        }
    }

    public AdherenceReport report(String memberId, String window) {
        DateRange range = DateRange.parse(window);
        LoadedMember loaded = loader.load(memberId);
        List<ObligationResult> results = loader.score(loaded, range);
        SummaryRequest request = new SummaryRequest(loaded.roster(), results, loaded.shields(), range);
        List<PeriodSummary> byHitch = request.byHitch();
        return new AdherenceReport(
                range,
                loaded.asOf(),
                results,
                request.summarise(),
                request.byHabit(),
                byHitch,
                Analytics.byRole(Shield.unexcused(results, loaded.shields(), memberId), result -> loaded.roster()
                        .roleFor(result.obligation().dueOn())),
                Analytics.trendOf(byHitch),
                Analytics.busiestHour(loaded.resolved())
                        .map(HourBucket::hour)
                        .map(OptionalInt::of)
                        .orElse(OptionalInt.empty()));
    }

    /** The run of one habit over a window. */
    public StreakSummary streak(String memberId, String habitId, String window) {
        DateRange range = DateRange.parse(window);
        LoadedMember loaded = loader.load(memberId);
        List<ObligationResult> results = loader.scoreOne(loaded, loaded.habitOf(habitId), range);
        return StreakSummary.compute(results, loaded.shields(), memberId);
    }

    /**
     * The breaches of the rest hour rules in a window, read from a habit that logs rest in hours.
     * A habit measured in anything else is refused, because a count of seven is not seven hours.
     */
    public List<RestBreach> restHours(String memberId, String habitId, String window, RestHoursRule rule) {
        DateRange range = DateRange.parse(window);
        LoadedMember loaded = loader.load(memberId);
        Habit habit = loaded.habitOf(habitId);
        if (habit.target().map(target -> target.unit() != TargetUnit.HOURS).orElse(true)) {
            throw ValidationException.field("habitId", "does not log rest in hours: " + habit.name());
        }
        List<ResolvedCheckIn> logged = loaded.resolved().stream()
                .filter(item -> item.checkIn().habitId().equals(habitId))
                .toList();
        return rule.check(logged, range);
    }

    /** What is still open in a window and not yet done enough times, less anything a shield excuses. */
    public List<ObligationResult> open(String memberId, String window) {
        LoadedMember loaded = loader.load(memberId);
        List<ObligationResult> owed =
                Shield.unexcused(loader.score(loaded, DateRange.parse(window)), loaded.shields(), memberId);
        return owed.stream()
                .filter(result -> result.status() == AdherenceStatus.PENDING
                        && result.count() < result.obligation().times())
                .toList();
    }
}
