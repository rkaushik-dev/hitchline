package io.github.rkaushikdev.hitchline.domain.nudge;

import io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.rotation.Zoning;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.ClockTime;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Works out which open obligations are worth a nudge, and when. A nudge lands a policy's lead
 * time before the end of the local day the obligation closes on, measured on the clock that day
 * belongs to, pushed out of quiet hours, and never in the past.
 */
public final class NudgePlanner {

    private NudgePlanner() {}

    /** The wall clock minute a nudge wants to land on, read back from midnight. */
    private static int targetMinute(NudgePolicy policy) {
        return Math.max(0, Math.min(ClockTime.MINUTES_IN_DAY - 1, ClockTime.MINUTES_IN_DAY - policy.leadMinutes()));
    }

    private static NudgeReason reasonFor(ObligationResult result) {
        if (result.obligation().span() > 1) {
            return NudgeReason.LAST_CHANCE;
        }
        return result.count() == 0 ? NudgeReason.NOTHING_YET : NudgeReason.PART_WAY;
    }

    private static Optional<PlannedNudge> plannedFor(
            Roster roster, NudgePolicy policy, ObligationResult result, Instant asOf) {
        CivilDate date = result.obligation().window().to();
        if (!policy.nudgesOnRole(roster.roleFor(date))) {
            return Optional.empty();
        }
        ClockTime wanted = ClockTime.fromMinutesSinceMidnight(targetMinute(policy));
        ClockTime allowed = policy.firstAllowedTime(wanted);
        // Quiet hours that run past midnight would push the nudge into the day after the window
        // closes, which is too late to be worth sending.
        if (ClockTime.compare(allowed, wanted) < 0) {
            return Optional.empty();
        }
        Instant at = Zones.zonedInstant(Zoning.zoneOn(roster.member(), date).zone(), date, allowed);
        if (at.isBefore(asOf)) {
            return Optional.empty();
        }
        return Optional.of(new PlannedNudge(
                result.obligation().habitId(), result.obligation().key(), at, date, reasonFor(result)));
    }

    /** Nudges for every pending obligation not yet done enough times, in time order and capped. */
    public static List<PlannedNudge> plan(
            Roster roster, NudgePolicy policy, List<ObligationResult> results, Instant asOf) {
        if (policy.isSilent()) {
            return List.of();
        }
        List<PlannedNudge> planned = new ArrayList<>();
        for (ObligationResult result : results) {
            if (result.status() != AdherenceStatus.PENDING
                    || result.count() >= result.obligation().times()) {
                continue;
            }
            plannedFor(roster, policy, result, asOf).ifPresent(planned::add);
        }
        planned.sort(Comparator.comparing(PlannedNudge::at));
        return capPerDay(planned, policy);
    }

    /**
     * Keeps the earliest few on any one day and drops anything too close behind. Nudges that fall
     * on the same minute ride along with the one before them, because two habits due at the same
     * time are one message on the handset.
     */
    public static List<PlannedNudge> capPerDay(List<PlannedNudge> planned, NudgePolicy policy) {
        Map<String, List<PlannedNudge>> heads = new HashMap<>();
        List<PlannedNudge> kept = new ArrayList<>();
        long gapMs = policy.minGapMinutes() * 60_000L;
        for (PlannedNudge nudge : planned) {
            List<PlannedNudge> sent = heads.computeIfAbsent(nudge.onDate().toString(), day -> new ArrayList<>());
            PlannedNudge last = sent.isEmpty() ? null : sent.get(sent.size() - 1);
            if (last != null && last.at().equals(nudge.at())) {
                kept.add(nudge);
                continue;
            }
            if (sent.size() >= policy.maxPerDay()) {
                continue;
            }
            if (last != null && nudge.at().toEpochMilli() - last.at().toEpochMilli() < gapMs) {
                continue;
            }
            sent.add(nudge);
            kept.add(nudge);
        }
        return kept;
    }

    public static List<PlannedNudge> forHabit(List<PlannedNudge> planned, String habitId) {
        return planned.stream().filter(nudge -> nudge.habitId().equals(habitId)).toList();
    }
}
