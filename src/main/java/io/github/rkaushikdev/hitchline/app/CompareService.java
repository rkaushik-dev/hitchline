package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.adherence.Shield;
import io.github.rkaushikdev.hitchline.domain.adherence.StatusTally;
import io.github.rkaushikdev.hitchline.domain.crew.AccessRequest;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.crew.CrewRole;
import io.github.rkaushikdev.hitchline.domain.crew.Membership;
import io.github.rkaushikdev.hitchline.domain.crew.SharingPreference;
import io.github.rkaushikdev.hitchline.domain.crew.Viewer;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.util.Collation;
import java.util.Comparator;
import java.util.List;

/**
 * A crew read side by side. Two rules matter here and they pull against each other: a lead needs
 * to see whether the crew is keeping up, and a member who has not shared a habit must not appear
 * in a list because of it. So the comparison only ever counts what the viewer is allowed to read,
 * and says how much it left out. Nobody drops down the list for days a shield excuses.
 */
public final class CompareService {

    private final Ports ports;
    private final MemberLoader loader;

    public CompareService(Ports ports) {
        this.ports = ports;
        this.loader = new MemberLoader(ports);
    }

    /**
     * One member's line in a comparison.
     *
     * @param memberId who
     * @param displayName their name
     * @param owed the obligations the viewer may read
     * @param met how many of those were met
     * @param partial how many closed partly done
     * @param missed how many were missed outright
     * @param rate the adherence rate over them
     * @param hidden habits left out because the viewer may not read them
     * @param ranked false when too little was owed for the rate to mean anything
     */
    public record CrewLine(
            String memberId,
            String displayName,
            int owed,
            int met,
            int partial,
            int missed,
            double rate,
            int hidden,
            boolean ranked) {

        /** What closed, met or not; pending obligations are left out. */
        public int settled() {
            return met + partial + missed;
        }
    }

    /**
     * A crew compared.
     *
     * @param crewId the crew
     * @param lines the ranked members best rate first, then by name, and then the rest by name
     * @param rate what the ranked members met over what they settled, one when nothing settled
     */
    public record CrewComparison(String crewId, List<CrewLine> lines, double rate) {

        public CrewComparison {
            lines = List.copyOf(lines);
        }
    }

    private static final Comparator<CrewLine> BEST_FIRST = Comparator.comparing((CrewLine line) -> !line.ranked())
            .thenComparing(Comparator.comparingDouble((CrewLine line) -> line.ranked() ? -line.rate() : 0))
            .thenComparing(CrewLine::displayName, Collation.ORDER);

    /** The crew as the viewer may see it, over a window, with membership read on {@code on}. */
    public CrewComparison crew(String viewerId, String crewId, String window, String on) {
        return crew(viewerId, crewId, window, on, 0);
    }

    /**
     * The crew as {@link #crew(String, String, String, String)} reads it, ranking only those owed
     * at least {@code minOwed}. Somebody back from leave with two days owed would otherwise top
     * the crew on a perfect two out of two, so they are listed after the ranked members and left
     * out of the crew's rate.
     */
    public CrewComparison crew(String viewerId, String crewId, String window, String on, int minOwed) {
        if (ports.crews().byId(crewId).isEmpty()) {
            throw new NotFoundException("crew", crewId);
        }
        if (minOwed < 0) {
            throw ValidationException.field("minOwed", "must be zero or more, got " + minOwed);
        }
        CivilDate date = CivilDate.parse(on);
        DateRange range = DateRange.parse(window);
        List<Membership> memberships = ports.crews().memberships();
        Viewer viewer = viewerOf(memberships, viewerId, crewId);
        List<CrewLine> lines = Membership.membersOn(memberships, crewId, date).stream()
                .flatMap(membership -> ports.members().byId(membership.memberId()).stream())
                .map(member -> lineFor(viewer, member, memberships, date, range, minOwed))
                .sorted(BEST_FIRST)
                .toList();
        int settled = lines.stream()
                .filter(CrewLine::ranked)
                .mapToInt(CrewLine::settled)
                .sum();
        int met =
                lines.stream().filter(CrewLine::ranked).mapToInt(CrewLine::met).sum();
        return new CrewComparison(crewId, lines, settled == 0 ? 1 : met / (double) settled);
    }

    private static Viewer viewerOf(List<Membership> memberships, String viewerId, String crewId) {
        CrewRole role = memberships.stream()
                .filter(row -> row.memberId().equals(viewerId) && row.crewId().equals(crewId))
                .findFirst()
                .map(Membership::role)
                .orElse(CrewRole.MEMBER);
        return new Viewer(viewerId, role);
    }

    /** One member's line: only the habits the viewer may read, less what a shield excuses. */
    private CrewLine lineFor(
            Viewer viewer,
            CrewMember member,
            List<Membership> memberships,
            CivilDate date,
            DateRange range,
            int minOwed) {
        SharingPreference preference =
                ports.sharing().byMember(member.id()).orElseGet(() -> SharingPreference.of(member.id()));
        LoadedMember loaded = loader.load(member.id());
        List<Habit> readable = loaded.habits().stream()
                .filter(habit ->
                        SharingPreference.canRead(new AccessRequest(viewer, preference, habit.id(), memberships, date)))
                .toList();
        List<ObligationResult> scored = readable.stream()
                .flatMap(habit -> loader.scoreOne(loaded, habit, range).stream())
                .toList();
        StatusTally counts = StatusTally.of(Shield.unexcused(scored, loaded.shields(), member.id()));
        return new CrewLine(
                member.id(),
                member.displayName(),
                counts.owed(),
                counts.met(),
                counts.partial(),
                counts.missed(),
                counts.rate(),
                loaded.habits().size() - readable.size(),
                counts.owed() >= minOwed);
    }
}
