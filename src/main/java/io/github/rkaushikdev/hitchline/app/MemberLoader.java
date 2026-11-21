package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.schedule.Expansion;
import io.github.rkaushikdev.hitchline.domain.schedule.Obligation;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads one member whole and scores their habits. Three services used to assemble this for
 * themselves and they drifted: the nudge planner counted voided check ins for a fortnight because
 * only two of the three copies filtered them out.
 */
public final class MemberLoader {

    private final Ports ports;

    public MemberLoader(Ports ports) {
        this.ports = ports;
    }

    public LoadedMember load(String memberId) {
        CrewMember member =
                ports.members().byId(memberId).orElseThrow(() -> new NotFoundException("crew member", memberId));
        List<CheckIn> live = ports.checkIns().byMember(memberId).stream()
                .filter(entry -> entry.voidedAt().isEmpty())
                .toList();
        return new LoadedMember(
                Roster.of(member, ports.overrides().byMember(memberId)),
                ports.habits().byMember(memberId),
                ResolvedCheckIn.resolveAll(member, live),
                ports.shields().byMember(memberId),
                Zones.civilDateIn(member.siteZone(), ports.clock().now()));
    }

    /** Every habit of the member scored over {@code window}, habit by habit. */
    public List<ObligationResult> score(LoadedMember loaded, DateRange window) {
        List<ObligationResult> results = new ArrayList<>();
        for (Habit habit : loaded.habits()) {
            results.addAll(scoreOne(loaded, habit, window));
        }
        return results;
    }

    public List<ObligationResult> scoreOne(LoadedMember loaded, Habit habit, DateRange window) {
        List<Obligation> obligations =
                Expansion.of(loaded.roster(), habit, window).expand();
        return ObligationResult.evaluateAll(habit, obligations, loaded.resolved(), loaded.asOf());
    }
}
