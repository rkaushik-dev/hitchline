package io.github.rkaushikdev.hitchline.domain.crew;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationAssignment;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Somebody working a rotation. The name is tidied and checked on the way in, and both zones must
 * be ones the platform knows.
 *
 * @param id the member's id
 * @param displayName the name shown on rosters, 2 to 80 characters
 * @param homeZone where they live, which is where an off shift day is measured
 * @param siteZone where they work, which is where an on shift day is measured
 * @param rotation the pattern they work and where it is anchored
 * @param status where they sit with the operator
 * @param joinedOn the day they joined
 */
public record CrewMember(
        String id,
        String displayName,
        String homeZone,
        String siteZone,
        RotationAssignment rotation,
        MemberStatus status,
        CivilDate joinedOn) {

    static final int NAME_MIN = 2;
    static final int NAME_MAX = 80;

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\u0000-\\u001f\\u007f]");
    private static final Map<MemberStatus, List<MemberStatus>> ALLOWED_MOVES = Map.of(
            MemberStatus.ACTIVE, List.of(MemberStatus.SUSPENDED, MemberStatus.DEPARTED),
            MemberStatus.SUSPENDED, List.of(MemberStatus.ACTIVE, MemberStatus.DEPARTED),
            MemberStatus.DEPARTED, List.of());

    public CrewMember {
        Objects.requireNonNull(id, "id");
        displayName = checkName(displayName);
        homeZone = Zones.zoneId(homeZone);
        siteZone = Zones.zoneId(siteZone);
        Objects.requireNonNull(rotation, "rotation");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(joinedOn, "joinedOn");
    }

    /** A new member, who starts out active. */
    public static CrewMember of(
            String id,
            String displayName,
            String homeZone,
            String siteZone,
            RotationAssignment rotation,
            CivilDate joinedOn) {
        return new CrewMember(id, displayName, homeZone, siteZone, rotation, MemberStatus.ACTIVE, joinedOn);
    }

    /** Squeezes runs of white space to one space and trims the ends. */
    public static String normaliseName(String raw) {
        return Texts.squeeze(raw);
    }

    /** Checks a display name and hands back its tidy form. */
    public static String checkName(String raw) {
        if (CONTROL_CHARS.matcher(raw).find()) {
            throw ValidationException.field("displayName", "must not contain control characters");
        }
        String name = normaliseName(raw);
        if (name.length() < NAME_MIN) {
            throw ValidationException.field("displayName", "must be at least " + NAME_MIN + " characters");
        }
        if (name.length() > NAME_MAX) {
            throw ValidationException.field("displayName", "must be at most " + NAME_MAX + " characters");
        }
        return name;
    }

    public boolean isActive() {
        return status == MemberStatus.ACTIVE;
    }

    public CrewMember rename(String newName) {
        return new CrewMember(id, newName, homeZone, siteZone, rotation, status, joinedOn);
    }

    /**
     * Moves the member to another status. Active and suspended members may move either way or
     * leave; nobody comes back once they have left. A move to the status they already have
     * changes nothing and hands back this member.
     */
    public CrewMember moveTo(MemberStatus next) {
        if (status == next) {
            return this;
        }
        if (!ALLOWED_MOVES.get(status).contains(next)) {
            throw ValidationException.field("status", "cannot go from " + status + " to " + next);
        }
        return new CrewMember(id, displayName, homeZone, siteZone, rotation, next, joinedOn);
    }

    public CrewMember reassign(RotationAssignment next) {
        if (status == MemberStatus.DEPARTED) {
            throw ValidationException.field("rotation", "cannot be changed for somebody who has left");
        }
        return new CrewMember(id, displayName, homeZone, siteZone, next, status, joinedOn);
    }

    public CrewMember relocate(String newSiteZone) {
        return new CrewMember(id, displayName, homeZone, newSiteZone, rotation, status, joinedOn);
    }

    /** Two letters for a roster grid, taken from the first and last word of the name. */
    public String initials() {
        List<String> words = Texts.split(displayName, " ").stream()
                .filter(word -> !word.isEmpty())
                .toList();
        String first = words.isEmpty() ? "" : words.get(0);
        String last = words.size() > 1 ? words.get(words.size() - 1) : "";
        return firstLetter(first) + firstLetter(last);
    }

    private static String firstLetter(String word) {
        return word.isEmpty() ? "" : word.substring(0, 1).toUpperCase(Locale.ROOT);
    }
}
