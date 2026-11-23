package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.NotFoundException;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
     */
    public record CrewLine(
            String memberId, String displayName, int owed, int met, int partial, int missed, double rate, int hidden) {}

    /**
     * A crew compared.
     *
     * @param crewId the crew
     * @param lines one per member, best rate first, then by name
     * @param rate everything met over everything owed, one when nothing was owed
     */
    public record CrewComparison(String crewId, List<CrewLine> lines, double rate) {

        public CrewComparison {
            lines = List.copyOf(lines);
        }
    }

    private static final Comparator<CrewLine> BEST_FIRST =
            Comparator.comparingDouble(CrewLine::rate).reversed().thenComparing(CrewLine::displayName, Collation.ORDER);

    /** The crew as the viewer may see it, over a window, with membership read on {@code on}. */
    public CrewComparison crew(String viewerId, String crewId, String window, String on) {
        if (ports.crews().byId(crewId).isEmpty()) {
            throw new NotFoundException("crew", crewId);
        }
        CivilDate date = CivilDate.parse(on);
        DateRange range = DateRange.parse(window);
        List<Membership> memberships = ports.crews().memberships();
        CrewRole viewerRole = memberships.stream()
                .filter(row -> row.memberId().equals(viewerId) && row.crewId().equals(crewId))
                .findFirst()
                .map(Membership::role)
                .orElse(CrewRole.MEMBER);
        Viewer viewer = new Viewer(viewerId, viewerRole);

        List<CrewLine> lines = new ArrayList<>();
        for (Membership membership : Membership.membersOn(memberships, crewId, date)) {
            Optional<CrewMember> found = ports.members().byId(membership.memberId());
            if (found.isEmpty()) {
                continue;
            }
            CrewMember member = found.get();
            SharingPreference preference =
                    ports.sharing().byMember(member.id()).orElseGet(() -> SharingPreference.of(member.id()));
            LoadedMember loaded = loader.load(member.id());
            int hidden = 0;
            List<ObligationResult> readable = new ArrayList<>();
            for (Habit habit : loaded.habits()) {
                boolean allowed =
                        SharingPreference.canRead(new AccessRequest(viewer, preference, habit.id(), memberships, date));
                if (!allowed) {
                    hidden += 1;
                    continue;
                }
                readable.addAll(loader.scoreOne(loaded, habit, range));
            }
            List<ObligationResult> owed = Shield.unexcused(readable, loaded.shields(), member.id());
            StatusTally counts = StatusTally.of(owed);
            lines.add(new CrewLine(
                    member.id(),
                    member.displayName(),
                    counts.owed(),
                    counts.met(),
                    counts.partial(),
                    counts.missed(),
                    counts.rate(),
                    hidden));
        }
        lines.sort(BEST_FIRST);
        int owed = lines.stream().mapToInt(CrewLine::owed).sum();
        int met = lines.stream().mapToInt(CrewLine::met).sum();
        return new CrewComparison(crewId, lines, owed == 0 ? 1 : met / (double) owed);
    }
}
