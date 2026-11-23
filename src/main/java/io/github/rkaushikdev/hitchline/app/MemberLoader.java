package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.adherence.Proration;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.schedule.Expansion;
import io.github.rkaushikdev.hitchline.domain.schedule.Obligation;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.util.List;

/**
 * Loads one member whole and scores their habits. Three services used to assemble this for
 * themselves and they drifted: the nudge planner counted voided check ins for a fortnight because
 * only two of the three copies filtered them out. Scoring prorates anything measured over several
 * days around the member's shields, so every report asks for the same reduced count.
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
        return loaded.habits().stream()
                .flatMap(habit -> scoreOne(loaded, habit, window).stream())
                .toList();
    }

    /** One habit expanded over {@code window}, prorated around the shields and scored. */
    public List<ObligationResult> scoreOne(LoadedMember loaded, Habit habit, DateRange window) {
        List<Obligation> obligations = Proration.prorateAll(
                Expansion.of(loaded.roster(), habit, window).expand(),
                loaded.shields(),
                loaded.roster().member().id());
        return ObligationResult.evaluateAll(habit, obligations, loaded.resolved(), loaded.asOf());
    }
}
