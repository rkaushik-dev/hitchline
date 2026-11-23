package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.IdKind;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.crew.AccessRequest;
import io.github.rkaushikdev.hitchline.domain.crew.Crew;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.crew.CrewRole;
import io.github.rkaushikdev.hitchline.domain.crew.Membership;
import io.github.rkaushikdev.hitchline.domain.crew.PersonsOnBoard;
import io.github.rkaushikdev.hitchline.domain.crew.SharingPreference;
import io.github.rkaushikdev.hitchline.domain.crew.Viewer;
import io.github.rkaushikdev.hitchline.domain.crew.Visibility;
import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.util.Collation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Crews, who is in them and who may read whose numbers. The sharing rules live next to the
 * membership rules on purpose: every access question needs both.
 */
public final class CrewService {

    private final Ports ports;

    public CrewService(Ports ports) {
        this.ports = ports;
    }

    private CrewMember requireMember(String memberId) {
        return ports.members().byId(memberId).orElseThrow(() -> new NotFoundException("crew member", memberId));
    }

    private SharingPreference preferenceOf(String memberId) {
        return ports.sharing().byMember(memberId).orElseGet(() -> SharingPreference.of(memberId));
    }

    private CrewRole roleOn(String memberId, CivilDate on) {
        return Membership.crewOf(ports.crews().memberships(), memberId, on)
                .map(Membership::role)
                .orElse(CrewRole.MEMBER);
    }

    public Crew create(String name, String site, String siteZone) {
        Crew entry = new Crew(Ids.crewId(ports.ids().next(IdKind.CREW)), name, site, siteZone);
        ports.crews().save(entry);
        return entry;
    }

    /** Puts a member in a crew from a date, refusing a second crew on any day. */
    public Membership enrol(String crewId, String memberId, String from, Optional<CrewRole> role) {
        requireMember(memberId);
        if (ports.crews().byId(crewId).isEmpty()) {
            throw new NotFoundException("crew", crewId);
        }
        Membership entry =
                new Membership(crewId, memberId, role.orElse(CrewRole.MEMBER), CivilDate.parse(from), Optional.empty());
        List<Membership> all = new ArrayList<>(ports.crews().memberships());
        all.add(entry);
        Membership.checkOneCrewPerDay(all);
        ports.crews().addMembership(entry);
        return entry;
    }

    /** Ends a membership on {@code on}, in place. */
    public Membership release(String crewId, String memberId, String on) {
        Membership held = ports.crews().memberships().stream()
                .filter(row -> row.crewId().equals(crewId) && row.memberId().equals(memberId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("membership", memberId));
        Membership ended = held.endOn(CivilDate.parse(on));
        ports.crews().replaceMembership(ended);
        return ended;
    }

    /** Everybody in the crew on a date, by name. */
    public List<CrewMember> membersOfCrew(String crewId, String on) {
        List<CrewMember> found = new ArrayList<>();
        for (Membership row : Membership.membersOn(ports.crews().memberships(), crewId, CivilDate.parse(on))) {
            ports.members().byId(row.memberId()).ifPresent(found::add);
        }
        found.sort(Comparator.comparing(CrewMember::displayName, Collation.ORDER));
        return found;
    }

    /** The crew's persons on board for each day of {@code window}, with every member's holds read in. */
    public List<PersonsOnBoard> personsOnBoard(String crewId, String window) {
        if (ports.crews().byId(crewId).isEmpty()) {
            throw new NotFoundException("crew", crewId);
        }
        List<Membership> memberships = ports.crews().memberships();
        Map<String, Roster> rosters = new HashMap<>();
        memberships.stream()
                .filter(row -> row.crewId().equals(crewId))
                .map(Membership::memberId)
                .distinct()
                .forEach(memberId -> ports.members()
                        .byId(memberId)
                        .ifPresent(member -> rosters.put(
                                memberId, Roster.of(member, ports.overrides().byMember(memberId)))));
        return PersonsOnBoard.count(memberships, rosters, crewId, DateRange.parse(window));
    }

    public Optional<Crew> crewFor(String memberId, String on) {
        return Membership.crewOf(ports.crews().memberships(), memberId, CivilDate.parse(on))
                .flatMap(row -> ports.crews().byId(row.crewId()));
    }

    /** The member's sharing settings, closed when they have made none. */
    public SharingPreference visibilityFor(String memberId) {
        return preferenceOf(memberId);
    }

    public SharingPreference setVisibility(String memberId, Visibility fallback) {
        requireMember(memberId);
        SharingPreference current = preferenceOf(memberId);
        SharingPreference updated = new SharingPreference(memberId, fallback, current.perHabit());
        ports.sharing().save(updated);
        return updated;
    }

    public SharingPreference setHabitVisibility(String memberId, String habitId, Visibility visibility) {
        requireMember(memberId);
        SharingPreference updated = preferenceOf(memberId).withHabitVisibility(habitId, visibility);
        ports.sharing().save(updated);
        return updated;
    }

    /** Whether the viewer may read the subject's habit on a date. */
    public boolean mayRead(String viewerId, String subjectId, String habitId, String on) {
        CivilDate date = CivilDate.parse(on);
        Viewer viewer = new Viewer(viewerId, roleOn(viewerId, date));
        return SharingPreference.canRead(new AccessRequest(
                viewer, preferenceOf(subjectId), habitId, ports.crews().memberships(), date));
    }
}
