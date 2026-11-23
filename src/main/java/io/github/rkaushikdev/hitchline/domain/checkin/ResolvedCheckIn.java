package io.github.rkaushikdev.hitchline.domain.checkin;

import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationDay;
import io.github.rkaushikdev.hitchline.domain.rotation.Zoning;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A check in placed on the rotation day it belongs to. Everything that counts check ins works on
 * these rather than on raw instants, because two entries an hour apart can belong to different
 * days and two a day apart to the same one.
 *
 * @param checkIn the check in
 * @param day the rotation day it belongs to
 */
public record ResolvedCheckIn(CheckIn checkIn, RotationDay day) {

    /** How close behind a counted check in a second one has to be to read as the same tap twice. */
    public static final long REPEAT_TAP_MS = 2 * 60_000L;

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

    /**
     * The ids of the check ins that are a tap repeated: a second check in on the same habit within
     * two minutes of one already counted, carrying the same reading or none. A watch that loses
     * its connection mid send delivers the same tap again, and a person who taps twice to be sure
     * did the thing once. The one counted is the earliest, and a repeat never starts a new window.
     */
    public static Set<String> repeatTaps(List<ResolvedCheckIn> resolved) {
        List<CheckIn> ordered = resolved.stream()
                .map(ResolvedCheckIn::checkIn)
                .sorted(CheckIn.ORDER)
                .toList();
        Map<String, CheckIn> lastCounted = new HashMap<>();
        Set<String> repeats = new HashSet<>();
        for (CheckIn entry : ordered) {
            CheckIn last = lastCounted.get(entry.habitId());
            boolean close =
                    last != null && entry.at().toEpochMilli() - last.at().toEpochMilli() <= REPEAT_TAP_MS;
            if (close && last.reading().equals(entry.reading())) {
                repeats.add(entry.id());
                continue;
            }
            lastCounted.put(entry.habitId(), entry);
        }
        return Set.copyOf(repeats);
    }

    /** The check ins with every repeated tap left out, in the order given. */
    public static List<ResolvedCheckIn> withoutRepeatTaps(List<ResolvedCheckIn> resolved) {
        Set<String> repeats = repeatTaps(resolved);
        return resolved.stream()
                .filter(item -> !repeats.contains(item.checkIn.id()))
                .toList();
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
