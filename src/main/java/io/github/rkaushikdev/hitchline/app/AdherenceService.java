package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus;
import io.github.rkaushikdev.hitchline.domain.adherence.Analytics;
import io.github.rkaushikdev.hitchline.domain.adherence.FreezeBudget;
import io.github.rkaushikdev.hitchline.domain.adherence.FrozenStreak;
import io.github.rkaushikdev.hitchline.domain.adherence.HabitSummary;
import io.github.rkaushikdev.hitchline.domain.adherence.HourBucket;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.adherence.PeriodSummary;
import io.github.rkaushikdev.hitchline.domain.adherence.Quota;
import io.github.rkaushikdev.hitchline.domain.adherence.QuotaReading;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * @param cleanHitches hitches in a row, back from the latest, cleared with nothing missed
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
            OptionalInt busiestHour,
            int cleanHitches) {

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
                Analytics.byRole(unexcused(loaded, results), result -> loaded.roster()
                        .roleFor(result.obligation().dueOn())),
                Analytics.trendOf(byHitch),
                Analytics.busiestHour(loaded.resolved())
                        .map(HourBucket::hour)
                        .map(OptionalInt::of)
                        .orElse(OptionalInt.empty()),
                Analytics.cleanHitchesInARow(byHitch));
    }

    /** The results no shield of the member excuses: what every count but the streak reads. */
    private static List<ObligationResult> unexcused(LoadedMember loaded, List<ObligationResult> results) {
        return Shield.unexcused(
                results, loaded.shields(), loaded.roster().member().id());
    }

    private List<ObligationResult> owed(LoadedMember loaded, String window) {
        return unexcused(loaded, loader.score(loaded, DateRange.parse(window)));
    }

    /**
     * The end of hitch note for a window, usually the hitch just finished: the summary, a line per
     * habit with the run still going on it, and when the next trip starts.
     */
    public Digest digest(String memberId, String window) {
        DateRange range = DateRange.parse(window);
        LoadedMember loaded = loader.load(memberId);
        List<ObligationResult> results = loader.score(loaded, range);
        Map<String, StreakSummary> streaks = new LinkedHashMap<>();
        for (Habit habit : loaded.habits()) {
            List<ObligationResult> mine = results.stream()
                    .filter(result -> result.obligation().habitId().equals(habit.id()))
                    .toList();
            streaks.put(habit.id(), StreakSummary.compute(mine, loaded.shields(), memberId));
        }
        PeriodSummary summary = new SummaryRequest(loaded.roster(), results, loaded.shields(), range).summarise();
        return new Digest(loaded.roster().member(), summary, loaded.habits(), unexcused(loaded, results), streaks);
    }

    /** The run of one habit over a window. */
    public StreakSummary streak(String memberId, String habitId, String window) {
        LoadedMember loaded = loader.load(memberId);
        return StreakSummary.compute(scoredHabit(loaded, habitId, window), loaded.shields(), memberId);
    }

    /** The run of one habit over a window, carrying as many misses as {@code budget} allows. */
    public FrozenStreak frozenStreak(String memberId, String habitId, String window, FreezeBudget budget) {
        LoadedMember loaded = loader.load(memberId);
        return FrozenStreak.compute(scoredHabit(loaded, habitId, window), loaded.shields(), memberId, budget);
    }

    private List<ObligationResult> scoredHabit(LoadedMember loaded, String habitId, String window) {
        return loader.scoreOne(loaded, loaded.habitOf(habitId), DateRange.parse(window));
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

    /**
     * The member's window read against a quota. Only what a shield does not excuse can count
     * against it, the same as in the summary.
     */
    public QuotaReading quota(String memberId, String window, Quota quota) {
        return quota.read(owed(loader.load(memberId), window));
    }

    /** What is still open in a window and not yet done enough times, less anything a shield excuses. */
    public List<ObligationResult> open(String memberId, String window) {
        return owed(loader.load(memberId), window).stream()
                .filter(result -> result.status() == AdherenceStatus.PENDING
                        && result.count() < result.obligation().times())
                .toList();
    }
}
