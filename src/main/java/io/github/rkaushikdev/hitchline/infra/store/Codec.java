package io.github.rkaushikdev.hitchline.infra.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.adherence.Shield;
import io.github.rkaushikdev.hitchline.domain.adherence.ShieldReason;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.crew.Crew;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.crew.CrewRole;
import io.github.rkaushikdev.hitchline.domain.crew.MemberStatus;
import io.github.rkaushikdev.hitchline.domain.crew.Membership;
import io.github.rkaushikdev.hitchline.domain.crew.SharingPreference;
import io.github.rkaushikdev.hitchline.domain.crew.Visibility;
import io.github.rkaushikdev.hitchline.domain.habit.Cadence;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitStatus;
import io.github.rkaushikdev.hitchline.domain.habit.HabitTarget;
import io.github.rkaushikdev.hitchline.domain.nudge.NudgeChannel;
import io.github.rkaushikdev.hitchline.domain.nudge.NudgePolicy;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.OverrideReason;
import io.github.rkaushikdev.hitchline.domain.rotation.RosterOverride;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationAssignment;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationPattern;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Json;
import io.github.rkaushikdev.hitchline.util.Numbers;
import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Records on their way to and from the journal, each as one JSON object. Decoding goes back
 * through the domain constructors rather than trusting the row, so a row that was hand edited into
 * something impossible is rejected at load rather than at some later report.
 */
public final class Codec {

    private Codec() {}

    /** A member's nudge policy as stored. */
    public record StoredPolicy(String memberId, NudgePolicy policy) {}

    /** The type a stored value has, in the words a stored-row failure uses. */
    static String typeOf(JsonNode value) {
        if (value == null || value.isMissingNode()) {
            return "undefined";
        }
        if (value.isTextual()) {
            return "string";
        }
        if (value.isNumber()) {
            return "number";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        return "object";
    }

    /** A stored value as plain text, used where a key is built from a row. */
    static String keyText(JsonNode row, String key) {
        JsonNode value = row == null ? null : row.get(key);
        if (value == null || value.isMissingNode()) {
            return "undefined";
        }
        if (value.isNull()) {
            return "null";
        }
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isNumber()) {
            return Numbers.format(value.doubleValue());
        }
        return Json.stringify(value);
    }

    private static JsonNode field(JsonNode row, String key) {
        return row == null ? null : row.get(key);
    }

    static String text(JsonNode row, String key) {
        JsonNode value = field(row, key);
        if (value == null || !value.isTextual()) {
            throw ValidationException.field(key, "must be a string in a stored row, got " + typeOf(value));
        }
        return value.textValue();
    }

    static Optional<String> optionalText(JsonNode row, String key) {
        JsonNode value = field(row, key);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            throw ValidationException.field(key, "must be a string when present, got " + typeOf(value));
        }
        return Optional.of(value.textValue());
    }

    static double count(JsonNode row, String key) {
        JsonNode value = field(row, key);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw ValidationException.field(key, "must be a number in a stored row");
        }
        return value.doubleValue();
    }

    static OptionalDouble optionalCount(JsonNode row, String key) {
        JsonNode value = field(row, key);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(count(row, key));
    }

    /** A stored count that a domain rule needs whole, refused with that rule's own words. */
    static int whole(JsonNode row, String key, String rule) {
        double value = count(row, key);
        if (!Numbers.isInteger(value) || Math.abs(value) > Integer.MAX_VALUE) {
            throw ValidationException.field(key, rule);
        }
        return (int) value;
    }

    static List<String> words(JsonNode row, String key) {
        JsonNode value = field(row, key);
        if (value == null || value.isMissingNode()) {
            return List.of();
        }
        List<String> listed = new ArrayList<>();
        boolean allText = value.isArray();
        if (allText) {
            for (JsonNode entry : value) {
                if (!entry.isTextual()) {
                    allText = false;
                    break;
                }
                listed.add(entry.textValue());
            }
        }
        if (!allText) {
            throw ValidationException.field(key, "must be a list of strings");
        }
        return listed;
    }

    static <E extends Enum<E> & WireValue> E word(JsonNode row, String key, Class<E> type) {
        String value = text(row, key);
        return WireValue.fromWire(type, value)
                .orElseThrow(() -> ValidationException.field(key, "is not a known value in a stored row: " + value));
    }

    private static ArrayNode list(List<String> values) {
        ArrayNode array = Json.array();
        values.forEach(array::add);
        return array;
    }

    public static ObjectNode encodeMember(CrewMember member) {
        ObjectNode row = Json.object();
        row.put("id", member.id());
        row.put("displayName", member.displayName());
        row.put("homeZone", member.homeZone());
        row.put("siteZone", member.siteZone());
        row.put("pattern", member.rotation().pattern().format());
        row.put("anchor", member.rotation().anchor().toString());
        row.put("phase", member.rotation().phase());
        row.put("status", member.status().wire());
        row.put("joinedOn", member.joinedOn().toString());
        return row;
    }

    public static CrewMember decodeMember(JsonNode row) {
        String id = Ids.crewMemberId(text(row, "id"));
        String displayName = text(row, "displayName");
        String homeZone = text(row, "homeZone");
        String siteZone = text(row, "siteZone");
        RotationPattern pattern = RotationPattern.parse(text(row, "pattern"));
        CivilDate anchor = CivilDate.parse(text(row, "anchor"));
        int phase = whole(row, "phase", "must be a whole number of days into the cycle");
        RotationAssignment rotation = RotationAssignment.of(pattern, anchor, phase);
        MemberStatus status = word(row, "status", MemberStatus.class);
        CivilDate joinedOn = CivilDate.parse(text(row, "joinedOn"));
        return new CrewMember(id, displayName, homeZone, siteZone, rotation, status, joinedOn);
    }

    public static ObjectNode encodeHabit(Habit entry) {
        ObjectNode row = Json.object();
        row.put("id", entry.id());
        row.put("memberId", entry.memberId());
        row.put("name", entry.name());
        row.put("cadence", entry.cadence().format());
        if (entry.target().isPresent()) {
            row.put("target", entry.target().get().format());
        } else {
            row.putNull("target");
        }
        row.put("status", entry.status().wire());
        row.put("startsOn", entry.startsOn().toString());
        if (entry.endsOn().isPresent()) {
            row.put("endsOn", entry.endsOn().get().toString());
        } else {
            row.putNull("endsOn");
        }
        row.set("tags", list(entry.tags()));
        row.put("graceDays", entry.graceDays());
        row.put("createdAt", Zones.formatInstant(entry.createdAt()));
        return row;
    }

    public static Habit decodeHabit(JsonNode row) {
        Optional<String> target = optionalText(row, "target");
        Optional<String> endsOn = optionalText(row, "endsOn");
        String id = Ids.habitId(text(row, "id"));
        String memberId = Ids.crewMemberId(text(row, "memberId"));
        String name = text(row, "name");
        Cadence cadence = Cadence.parse(text(row, "cadence"));
        Optional<HabitTarget> parsedTarget = target.map(HabitTarget::parse);
        HabitStatus status = word(row, "status", HabitStatus.class);
        CivilDate startsOn = CivilDate.parse(text(row, "startsOn"));
        Optional<CivilDate> parsedEnd = endsOn.map(CivilDate::parse);
        List<String> tags = words(row, "tags");
        int graceDays = whole(row, "graceDays", "must be between 0 and 7");
        return Habit.builder(id, memberId, name, cadence, startsOn, Zones.parseInstant(text(row, "createdAt")))
                .target(parsedTarget)
                .status(status)
                .endsOn(parsedEnd)
                .tags(tags)
                .graceDays(graceDays)
                .build();
    }

    public static ObjectNode encodeCheckIn(CheckIn entry) {
        ObjectNode row = Json.object();
        row.put("id", entry.id());
        row.put("habitId", entry.habitId());
        row.put("memberId", entry.memberId());
        row.put("at", Zones.formatInstant(entry.at()));
        row.put("recordedAt", Zones.formatInstant(entry.recordedAt()));
        if (entry.reading().isPresent()) {
            row.put("reading", entry.reading().getAsDouble());
        } else {
            row.putNull("reading");
        }
        row.put("note", entry.note());
        row.put("source", entry.source().wire());
        if (entry.idempotencyKey().isPresent()) {
            row.put("idempotencyKey", entry.idempotencyKey().get());
        } else {
            row.putNull("idempotencyKey");
        }
        if (entry.voidedAt().isPresent()) {
            row.put("voidedAt", Zones.formatInstant(entry.voidedAt().get()));
        } else {
            row.putNull("voidedAt");
        }
        return row;
    }

    public static CheckIn decodeCheckIn(JsonNode row) {
        Optional<String> voidedAt = optionalText(row, "voidedAt");
        CheckIn base = CheckIn.builder(
                        Ids.checkInId(text(row, "id")),
                        Ids.habitId(text(row, "habitId")),
                        Ids.crewMemberId(text(row, "memberId")),
                        Zones.parseInstant(text(row, "at")),
                        Zones.parseInstant(text(row, "recordedAt")),
                        word(row, "source", CheckInSource.class))
                .reading(optionalCount(row, "reading"))
                .note(optionalText(row, "note").orElse(""))
                .idempotencyKey(optionalText(row, "idempotencyKey"))
                .build();
        if (voidedAt.isEmpty()) {
            return base;
        }
        return new CheckIn(
                base.id(),
                base.habitId(),
                base.memberId(),
                base.at(),
                base.recordedAt(),
                base.reading(),
                base.note(),
                base.source(),
                base.idempotencyKey(),
                Optional.of(Zones.parseInstant(voidedAt.get())));
    }

    public static ObjectNode encodeOverride(RosterOverride entry) {
        ObjectNode row = Json.object();
        row.put("memberId", entry.memberId());
        row.put("range", entry.range().toString());
        row.put("role", entry.role().wire());
        row.put("reason", entry.reason().wire());
        row.put("note", entry.note());
        row.put("enteredAt", Zones.formatInstant(entry.enteredAt()));
        return row;
    }

    public static RosterOverride decodeOverride(JsonNode row) {
        String memberId = Ids.crewMemberId(text(row, "memberId"));
        DateRange range = DateRange.parse(text(row, "range"));
        DayRole role = word(row, "role", DayRole.class);
        OverrideReason reason = word(row, "reason", OverrideReason.class);
        String note = optionalText(row, "note").orElse("");
        return new RosterOverride(memberId, range, role, reason, note, Zones.parseInstant(text(row, "enteredAt")));
    }

    public static ObjectNode encodeShield(Shield entry) {
        ObjectNode row = Json.object();
        row.put("memberId", entry.memberId());
        row.put("range", entry.range().toString());
        row.put("reason", entry.reason().wire());
        row.put("note", entry.note());
        if (entry.grantedBy().isPresent()) {
            row.put("grantedBy", entry.grantedBy().get());
        } else {
            row.putNull("grantedBy");
        }
        row.put("grantedAt", Zones.formatInstant(entry.grantedAt()));
        return row;
    }

    public static Shield decodeShield(JsonNode row) {
        Optional<String> grantedBy = optionalText(row, "grantedBy");
        String memberId = Ids.crewMemberId(text(row, "memberId"));
        DateRange range = DateRange.parse(text(row, "range"));
        ShieldReason reason = word(row, "reason", ShieldReason.class);
        String note = optionalText(row, "note").orElse("");
        Optional<String> granter = grantedBy.map(Ids::crewMemberId);
        return new Shield(memberId, range, reason, note, granter, Zones.parseInstant(text(row, "grantedAt")));
    }

    public static ObjectNode encodeCrew(Crew entry) {
        ObjectNode row = Json.object();
        row.put("id", entry.id());
        row.put("name", entry.name());
        row.put("site", entry.site());
        row.put("siteZone", entry.siteZone());
        return row;
    }

    public static Crew decodeCrew(JsonNode row) {
        return new Crew(Ids.crewId(text(row, "id")), text(row, "name"), text(row, "site"), text(row, "siteZone"));
    }

    public static ObjectNode encodeMembership(Membership entry) {
        ObjectNode row = Json.object();
        row.put("crewId", entry.crewId());
        row.put("memberId", entry.memberId());
        row.put("role", entry.role().wire());
        row.put("from", entry.from().toString());
        if (entry.to().isPresent()) {
            row.put("to", entry.to().get().toString());
        } else {
            row.putNull("to");
        }
        return row;
    }

    public static Membership decodeMembership(JsonNode row) {
        Optional<String> to = optionalText(row, "to");
        String crewId = Ids.crewId(text(row, "crewId"));
        String memberId = Ids.crewMemberId(text(row, "memberId"));
        CrewRole role = word(row, "role", CrewRole.class);
        CivilDate from = CivilDate.parse(text(row, "from"));
        return new Membership(crewId, memberId, role, from, to.map(CivilDate::parse));
    }

    public static ObjectNode encodeSharing(SharingPreference preference) {
        ObjectNode row = Json.object();
        row.put("memberId", preference.memberId());
        row.put("fallback", preference.fallback().wire());
        List<String> pairs = new ArrayList<>();
        preference.perHabit().forEach((id, visibility) -> pairs.add(id + "=" + visibility.wire()));
        row.set("perHabit", list(pairs));
        return row;
    }

    public static SharingPreference decodeSharing(JsonNode row) {
        Map<String, Visibility> perHabit = new LinkedHashMap<>();
        for (String entry : words(row, "perHabit")) {
            int at = entry.indexOf('=');
            if (at < 0) {
                throw ValidationException.field("perHabit", "is not a pair: " + entry);
            }
            String visibility = entry.substring(at + 1);
            perHabit.put(
                    Ids.habitId(entry.substring(0, at)),
                    Visibility.fromWire(visibility)
                            .orElseThrow(
                                    () -> ValidationException.field("perHabit", "is not a visibility: " + visibility)));
        }
        String memberId = Ids.crewMemberId(text(row, "memberId"));
        String fallback = text(row, "fallback");
        SharingPreference base = SharingPreference.of(memberId, fallback);
        return new SharingPreference(base.memberId(), base.fallback(), perHabit);
    }

    public static ObjectNode encodePolicy(String memberId, NudgePolicy policy) {
        ObjectNode row = Json.object();
        row.put("memberId", memberId);
        row.put("quietHours", policy.quietHours().toString());
        row.put("minGapMinutes", policy.minGapMinutes());
        row.put("maxPerDay", policy.maxPerDay());
        row.put("leadMinutes", policy.leadMinutes());
        row.set("roles", list(policy.roles().stream().map(DayRole::wire).toList()));
        row.put("channel", policy.channel().wire());
        return row;
    }

    public static StoredPolicy decodePolicy(JsonNode row) {
        String memberId = Ids.crewMemberId(text(row, "memberId"));
        NudgePolicy policy = NudgePolicy.builder()
                .quietHours(text(row, "quietHours"))
                .minGapMinutes(whole(row, "minGapMinutes", "must be between 1 and 1440"))
                .maxPerDay(whole(row, "maxPerDay", "must be between 0 and 12"))
                .leadMinutes(whole(row, "leadMinutes", "must be between 0 and 4320"))
                .roles(words(row, "roles"))
                .channel(word(row, "channel", NudgeChannel.class))
                .build();
        return new StoredPolicy(memberId, policy);
    }
}
