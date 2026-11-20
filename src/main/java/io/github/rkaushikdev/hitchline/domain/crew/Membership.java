package io.github.rkaushikdev.hitchline.domain.crew;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Somebody's place in a crew over a stretch of days. Membership is dated, because people move
 * between crews and a report about last March has to use the crew somebody was in at the time.
 *
 * @param crewId the crew
 * @param memberId the person
 * @param role what they are in it
 * @param from the first day, inclusive
 * @param to the last day, inclusive, when the membership has ended
 */
public record Membership(String crewId, String memberId, CrewRole role, CivilDate from, Optional<CivilDate> to) {

    public Membership {
        Objects.requireNonNull(crewId, "crewId");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isPresent() && to.get().isBefore(from)) {
            throw ValidationException.field("to", "ends before the membership starts");
        }
    }

    /** An open ended plain membership. */
    public static Membership of(String crewId, String memberId, CivilDate from) {
        return new Membership(crewId, memberId, CrewRole.MEMBER, from, Optional.empty());
    }

    public static Membership of(String crewId, String memberId, CivilDate from, CrewRole role) {
        return new Membership(crewId, memberId, role, from, Optional.empty());
    }

    public static Membership of(String crewId, String memberId, CivilDate from, CrewRole role, CivilDate to) {
        return new Membership(crewId, memberId, role, from, Optional.of(to));
    }

    /** True when the membership holds on {@code date}. */
    public boolean covers(CivilDate date) {
        if (CivilDate.compare(date, from) < 0) {
            return false;
        }
        return to.map(end -> CivilDate.compare(date, end) <= 0).orElse(true);
    }

    /** Ends the membership on {@code on}, which is its last day. */
    public Membership endOn(CivilDate on) {
        if (CivilDate.compare(on, from) < 0) {
            throw ValidationException.field("to", "is before the membership started");
        }
        return new Membership(crewId, memberId, role, from, Optional.of(on));
    }

    /** Everybody in {@code crewId} on {@code date}, in the order given. */
    public static List<Membership> membersOn(List<Membership> memberships, String crewId, CivilDate date) {
        return memberships.stream()
                .filter(entry -> entry.crewId.equals(crewId) && entry.covers(date))
                .toList();
    }

    /** The crew {@code memberId} was in on {@code date}. */
    public static Optional<Membership> crewOf(List<Membership> memberships, String memberId, CivilDate date) {
        return memberships.stream()
                .filter(entry -> entry.memberId.equals(memberId) && entry.covers(date))
                .findFirst();
    }

    /** The leads and coordinators of {@code crewId} on {@code date}. */
    public static List<Membership> leadsOf(List<Membership> memberships, String crewId, CivilDate date) {
        return membersOn(memberships, crewId, date).stream()
                .filter(entry -> entry.role != CrewRole.MEMBER)
                .toList();
    }

    /** Nobody may be in two crews on the same day. */
    public static void checkOneCrewPerDay(List<Membership> memberships) {
        Map<String, List<Membership>> byMember = new LinkedHashMap<>();
        for (Membership entry : memberships) {
            byMember.computeIfAbsent(entry.memberId, key -> new ArrayList<>()).add(entry);
        }
        for (Map.Entry<String, List<Membership>> held : byMember.entrySet()) {
            List<Membership> entries = held.getValue();
            for (int index = 0; index < entries.size(); index += 1) {
                for (int other = index + 1; other < entries.size(); other += 1) {
                    if (entries.get(index).overlaps(entries.get(other))) {
                        throw new ConflictException(held.getKey() + " is in two crews at once");
                    }
                }
            }
        }
    }

    private boolean overlaps(Membership other) {
        if (to.isPresent() && CivilDate.compare(other.from, to.get()) > 0) {
            return false;
        }
        return other.to.isEmpty() || CivilDate.compare(from, other.to.get()) <= 0;
    }
}
