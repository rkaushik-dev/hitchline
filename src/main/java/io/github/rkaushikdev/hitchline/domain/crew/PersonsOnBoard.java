package io.github.rkaushikdev.hitchline.domain.crew;

import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Who of a crew is on the installation on one day. An installation has a fixed number of beds and
 * lifeboat seats, so the persons on board count is the number a crewing plan is checked against
 * before anybody is held over, and somebody with charge of the crew has to be among them. Only an
 * on shift day puts somebody on board; a travel day is counted apart, because the helicopter that
 * brings one person in takes another out.
 *
 * @param date the day
 * @param onBoard members of the crew on shift that day
 * @param travelling members of the crew travelling that day
 * @param leads leads and coordinators among those on board
 */
public record PersonsOnBoard(CivilDate date, int onBoard, int travelling, int leads) {

    /**
     * One count per day of {@code window}, reading membership as it stood on each day and each
     * member's roster with the clerk's entries. A member with no roster in {@code rosters} is left
     * out, the way somebody who has left the books would be.
     */
    public static List<PersonsOnBoard> count(
            List<Membership> memberships, Map<String, Roster> rosters, String crewId, DateRange window) {
        List<PersonsOnBoard> days = new ArrayList<>();
        for (CivilDate date : window.days()) {
            int onBoard = 0;
            int travelling = 0;
            int leads = 0;
            for (Membership membership : Membership.membersOn(memberships, crewId, date)) {
                Optional<DayRole> role =
                        Optional.ofNullable(rosters.get(membership.memberId())).map(roster -> roster.roleFor(date));
                if (role.isEmpty()) {
                    continue;
                }
                if (role.get() == DayRole.ONSHIFT) {
                    onBoard += 1;
                    leads += membership.role() == CrewRole.MEMBER ? 0 : 1;
                } else if (role.get() == DayRole.TRAVEL) {
                    travelling += 1;
                }
            }
            days.add(new PersonsOnBoard(date, onBoard, travelling, leads));
        }
        return days;
    }

    /** True when more of the crew are on board than there are {@code beds}. */
    public boolean isOver(int beds) {
        return onBoard > beds;
    }

    /** True when some of the crew are on board and nobody with charge of them is. */
    public boolean hasNoLead() {
        return onBoard > 0 && leads == 0;
    }

    /** What is wrong with the day, such as {@code over 2 beds, no lead}, or nothing. */
    public List<String> problems(Optional<Integer> beds) {
        List<String> found = new ArrayList<>();
        beds.filter(this::isOver).ifPresent(limit -> found.add((onBoard - limit) + " over the " + limit + " beds"));
        if (hasNoLead()) {
            found.add("no lead on board");
        }
        return found;
    }
}
