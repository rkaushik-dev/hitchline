package io.github.rkaushikdev.hitchline.domain.checkin;

import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationDay;
import io.github.rkaushikdev.hitchline.domain.rotation.Zoning;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A check in placed on the rotation day it belongs to. Everything that counts check ins works on
 * these rather than on raw instants, because two entries an hour apart can belong to different
 * days and two a day apart to the same one.
 *
 * @param checkIn the check in
 * @param day the rotation day it belongs to
 */
public record ResolvedCheckIn(CheckIn checkIn, RotationDay day) {

    public static ResolvedCheckIn resolve(CrewMember member, CheckIn entry) {
        return new ResolvedCheckIn(entry, Zoning.rotationDayAt(member, entry.at()));
    }

    public static List<ResolvedCheckIn> resolveAll(CrewMember member, List<CheckIn> entries) {
        return entries.stream().map(entry -> resolve(member, entry)).toList();
    }

    public static List<ResolvedCheckIn> onDate(List<ResolvedCheckIn> resolved, CivilDate date) {
        return resolved.stream().filter(item -> item.day.date().equals(date)).toList();
    }

    public static List<ResolvedCheckIn> inRange(List<ResolvedCheckIn> resolved, DateRange range) {
        return resolved.stream().filter(item -> range.contains(item.day.date())).toList();
    }

    /** Keyed by the iso date in the order days are first met, so a caller can read a month in one pass. */
    public static Map<String, List<ResolvedCheckIn>> groupByDay(List<ResolvedCheckIn> resolved) {
        Map<String, List<ResolvedCheckIn>> groups = new LinkedHashMap<>();
        for (ResolvedCheckIn item : resolved) {
            groups.computeIfAbsent(item.day.date().toString(), key -> new ArrayList<>())
                    .add(item);
        }
        return groups;
    }

    public static int countInRange(List<ResolvedCheckIn> resolved, DateRange range) {
        return inRange(resolved, range).size();
    }

    /** The readings inside a window, with entries that carried none left out. */
    public static List<Double> readingsInRange(List<ResolvedCheckIn> resolved, DateRange range) {
        List<Double> readings = new ArrayList<>();
        for (ResolvedCheckIn item : inRange(resolved, range)) {
            item.checkIn.reading().ifPresent(readings::add);
        }
        return readings;
    }

    /** Entries whose two clocks disagreed about the date, worth showing in a report. */
    public static List<ResolvedCheckIn> straddling(List<ResolvedCheckIn> resolved) {
        return resolved.stream().filter(item -> item.day.straddled()).toList();
    }

    /** Every iso date with at least one entry, in order. */
    public static List<String> daysTouched(List<ResolvedCheckIn> resolved) {
        List<String> days = new ArrayList<>(groupByDay(resolved).keySet());
        days.sort(null);
        return days;
    }
}
