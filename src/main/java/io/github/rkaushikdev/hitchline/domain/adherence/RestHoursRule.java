package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.habit.TargetUnit;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.ArrayList;
import java.util.List;

/**
 * The rest hour rules a log of rest is held to. The maritime labour rules and the watchkeeping
 * code both ask for at least 10 hours of rest in any 24 and 77 in any seven days, split into no
 * more than two periods with one of them at least 6 hours long. Each check in on a rest habit is
 * one period of rest, read in hours, on the rotation day it landed on. A day with nothing logged
 * is not judged, because a missing log is the habit's business, and a week is only judged when all
 * seven of its days were logged.
 *
 * @param minDayHours the least rest in a day
 * @param minWeekHours the least rest in seven days
 * @param maxPeriods the most pieces a day's rest may come in
 * @param minLongestHours how long the longest piece has to be
 */
public record RestHoursRule(double minDayHours, double minWeekHours, int maxPeriods, double minLongestHours) {

    static final int WEEK_DAYS = 7;

    /** Ten in a day, 77 in a week, in no more than two periods with one of six hours. */
    public static final RestHoursRule DEFAULT = new RestHoursRule(10, 77, 2, 6);

    public RestHoursRule {
        if (minDayHours <= 0 || minDayHours > 24) {
            throw ValidationException.field("minDayHours", "must be more than 0 and at most 24");
        }
        if (minWeekHours <= 0 || minWeekHours > 24 * WEEK_DAYS) {
            throw ValidationException.field("minWeekHours", "must be more than 0 and at most 168");
        }
        if (maxPeriods < 1) {
            throw ValidationException.field("maxPeriods", "must be at least 1");
        }
        if (minLongestHours < 0 || minLongestHours > minDayHours) {
            throw ValidationException.field("minLongestHours", "must be from 0 to the daily least");
        }
    }

    /**
     * One day of logged rest.
     *
     * @param date the rotation day
     * @param hours the rest logged on it
     * @param periods how many check ins it was logged in
     * @param longest the longest of them
     */
    public record RestDay(CivilDate date, double hours, int periods, double longest) {}

    /** The days of {@code window} with anything logged, in order. */
    public static List<RestDay> days(List<ResolvedCheckIn> entries, DateRange window) {
        List<RestDay> days = new ArrayList<>();
        for (CivilDate date : window.days()) {
            List<Double> periods = ResolvedCheckIn.readingsInRange(entries, DateRange.singleDay(date));
            if (periods.isEmpty()) {
                continue;
            }
            double longest =
                    periods.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            days.add(new RestDay(date, TargetUnit.HOURS.sum(periods), periods.size(), longest));
        }
        return days;
    }

    /**
     * Every breach inside {@code window}, day rules first for each day, in date order. A short
     * week is reported against the seven days ending on the day it was found, and a run of short
     * weeks is reported once, from the first.
     */
    public List<RestBreach> check(List<ResolvedCheckIn> entries, DateRange window) {
        DateRange reach = DateRange.of(window.from().plusDays(1L - WEEK_DAYS), window.to());
        List<RestDay> logged = days(entries, reach);
        List<RestBreach> breaches = new ArrayList<>();
        DateRange lastWeek = null;
        for (RestDay day : logged) {
            if (!window.contains(day.date())) {
                continue;
            }
            DateRange single = DateRange.singleDay(day.date());
            if (day.hours() < minDayHours) {
                breaches.add(new RestBreach(RestRule.SHORT_DAY, single, day.hours(), day.periods()));
            }
            if (day.periods() > maxPeriods) {
                breaches.add(new RestBreach(RestRule.TOO_MANY_PERIODS, single, day.hours(), day.periods()));
            }
            if (day.longest() < minLongestHours) {
                breaches.add(new RestBreach(RestRule.NO_LONG_PERIOD, single, day.hours(), day.periods()));
            }
            DateRange week = DateRange.of(day.date().plusDays(1L - WEEK_DAYS), day.date());
            List<RestDay> inWeek =
                    logged.stream().filter(other -> week.contains(other.date())).toList();
            double total =
                    TargetUnit.HOURS.sum(inWeek.stream().map(RestDay::hours).toList());
            if (inWeek.size() == WEEK_DAYS && total < minWeekHours) {
                if (lastWeek == null || !lastWeek.contains(week.from())) {
                    int periods = inWeek.stream().mapToInt(RestDay::periods).sum();
                    breaches.add(new RestBreach(RestRule.SHORT_WEEK, week, total, periods));
                    lastWeek = week;
                }
            }
        }
        return breaches;
    }
}
