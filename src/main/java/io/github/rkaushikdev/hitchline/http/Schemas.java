package io.github.rkaushikdev.hitchline.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.github.rkaushikdev.hitchline.domain.ErrorDetail;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.adherence.ShieldReason;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.crew.CrewRole;
import io.github.rkaushikdev.hitchline.domain.crew.Visibility;
import io.github.rkaushikdev.hitchline.domain.habit.HabitStatus;
import io.github.rkaushikdev.hitchline.domain.nudge.NudgeChannel;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.OverrideReason;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Shapes for anything arriving over http. The schemas only check the shape, never the meaning: a
 * cadence is a string here and the domain decides whether it is a cadence, so there is one place
 * that knows what "3/hitch" means.
 *
 * <p>The body records are built only by these schemas, and only kept when every field was well
 * formed, so they carry what was read without checks of their own.
 */
public final class Schemas {

    private Schemas() {}

    private static final Schema.Text ISO_DATE =
            Schema.text().matching("\\d{4}-\\d{2}-\\d{2}", "must be written as YYYY-MM-DD");

    private static final Schema.Text DATE_WINDOW = Schema.text()
            .matching("\\d{4}-\\d{2}-\\d{2}\\.\\.\\d{4}-\\d{2}-\\d{2}", "must be written as YYYY-MM-DD..YYYY-MM-DD");

    private static final Schema.Text DATETIME = Schema.text().isoDateTime();

    private static final Schema.Num READING = Schema.number().min(0).max(100_000);

    private static final Schema.Text NOTE = Schema.text().max(500);

    private static final Schema.Text IDEMPOTENCY_KEY = Schema.text().min(8).max(64);

    private static final Schema<List<String>> TAGS =
            Schema.array(Schema.text().min(1).max(24), 0, 8);

    private static Schema.Text idOf(String prefix) {
        return Schema.text().matching(prefix + "_[0-9A-HJKMNP-TV-Z]{16}", "must be a " + prefix + " id");
    }

    /** A crew member id. */
    public static final Schema<String> MEMBER_ID = idOf("mem");

    /** A habit id. */
    public static final Schema<String> HABIT_ID = idOf("hab");

    /**
     * A new habit.
     *
     * @param memberId whose habit
     * @param name what it is called
     * @param cadence how often, as the domain writes it
     * @param target the target, as the domain writes it
     * @param startsOn the first day it asks
     * @param endsOn the last day it asks
     * @param tags short labels
     * @param graceDays how late a check in still counts
     */
    public record CreateHabitBody(
            String memberId,
            String name,
            String cadence,
            Optional<String> target,
            Optional<String> startsOn,
            Optional<String> endsOn,
            Optional<List<String>> tags,
            OptionalInt graceDays) {}

    public static final Schema<CreateHabitBody> CREATE_HABIT = Schema.object(fields -> new CreateHabitBody(
            fields.required("memberId", MEMBER_ID),
            fields.required("name", Schema.text().min(3).max(100)),
            fields.required("cadence", Schema.text().min(1).max(40)),
            fields.optional("target", Schema.text().min(1).max(60)),
            fields.optional("startsOn", ISO_DATE),
            fields.optional("endsOn", ISO_DATE),
            fields.optional("tags", TAGS),
            fields.optionalInt("graceDays", Schema.number().integer().min(0).max(7))));

    /**
     * A habit from the standard set.
     *
     * @param memberId whose habit
     * @param key which template
     * @param startsOn the first day it asks
     * @param endsOn the last day it asks
     */
    public record ApplyTemplateBody(String memberId, String key, Optional<String> startsOn, Optional<String> endsOn) {}

    public static final Schema<ApplyTemplateBody> APPLY_TEMPLATE = Schema.object(fields -> new ApplyTemplateBody(
            fields.required("memberId", MEMBER_ID),
            fields.required("key", Schema.text().matching("[a-z][a-z0-9-]{2,39}", "must be a template key")),
            fields.optional("startsOn", ISO_DATE),
            fields.optional("endsOn", ISO_DATE)));

    /**
     * A target to set, or none to clear the one a habit has.
     *
     * @param target the new target, as the domain writes it
     */
    public record TargetChange(Optional<String> target) {}

    /**
     * A change to a habit; at least one part is given.
     *
     * @param name a new name
     * @param cadence a new cadence
     * @param target a new target, or a request to clear it
     * @param tags new labels
     * @param status a new status
     */
    public record UpdateHabitBody(
            Optional<String> name,
            Optional<String> cadence,
            Optional<TargetChange> target,
            Optional<List<String>> tags,
            Optional<HabitStatus> status) {}

    public static final Schema<UpdateHabitBody> UPDATE_HABIT = Schema.object(
            fields -> new UpdateHabitBody(
                    fields.optional("name", Schema.text().min(3).max(100)),
                    fields.optional("cadence", Schema.text().min(1).max(40)),
                    fields.has("target")
                            ? Optional.of(new TargetChange(fields.nullable(
                                    "target", Schema.text().min(1).max(60))))
                            : Optional.empty(),
                    fields.optional("tags", TAGS),
                    fields.optional("status", Schema.oneOf(HabitStatus.class))),
            fields -> fields.given() > 0 ? Optional.empty() : Optional.of("must change something"));

    /**
     * A check in.
     *
     * @param habitId which habit
     * @param at when it happened, with an offset
     * @param reading the number it carried
     * @param note anything written
     * @param source how it reached us
     * @param idempotencyKey the key a retry carries
     */
    public record RecordCheckInBody(
            String habitId,
            Optional<String> at,
            OptionalDouble reading,
            Optional<String> note,
            Optional<CheckInSource> source,
            Optional<String> idempotencyKey) {}

    public static final Schema<RecordCheckInBody> RECORD_CHECK_IN = Schema.object(fields -> new RecordCheckInBody(
            fields.required("habitId", HABIT_ID),
            fields.optional("at", DATETIME),
            fields.optionalNumber("reading", READING),
            fields.optional("note", NOTE),
            fields.optional("source", Schema.oneOf(CheckInSource.class)),
            fields.optional("idempotencyKey", IDEMPOTENCY_KEY)));

    /**
     * One check in a handset queued while it had no signal.
     *
     * @param localId the handset's own id for it
     * @param habitId which habit
     * @param at when it happened, with an offset
     * @param reading the number it carried
     * @param note anything written
     * @param idempotencyKey the key a retry carries
     */
    public record SyncItem(
            String localId,
            String habitId,
            String at,
            OptionalDouble reading,
            Optional<String> note,
            Optional<String> idempotencyKey) {}

    /**
     * A batch from a handset.
     *
     * @param items what it queued, at most 500
     */
    public record SyncBody(List<SyncItem> items) {}

    public static final Schema<SyncBody> SYNC = Schema.object(fields -> new SyncBody(fields.required(
            "items",
            Schema.array(
                    Schema.object(item -> new SyncItem(
                            item.required("localId", Schema.text().min(1).max(64)),
                            item.required("habitId", HABIT_ID),
                            item.required("at", DATETIME),
                            item.optionalNumber("reading", READING),
                            item.optional("note", NOTE),
                            item.optional("idempotencyKey", IDEMPOTENCY_KEY))),
                    0,
                    500))));

    /**
     * A new reading for a check in.
     *
     * @param reading the reading, or none to clear it
     */
    public record AmendCheckInBody(OptionalDouble reading) {}

    public static final Schema<AmendCheckInBody> AMEND_CHECK_IN =
            Schema.object(fields -> new AmendCheckInBody(fields.requiredNullableNumber("reading", READING)));

    /**
     * A roster hold.
     *
     * @param memberId whose roster
     * @param range the days, {@code YYYY-MM-DD..YYYY-MM-DD}
     * @param role what the days become
     * @param reason why
     * @param note anything written with it
     */
    public record HoldBody(String memberId, String range, DayRole role, OverrideReason reason, Optional<String> note) {}

    public static final Schema<HoldBody> HOLD = Schema.object(fields -> new HoldBody(
            fields.required("memberId", MEMBER_ID),
            fields.required("range", DATE_WINDOW),
            fields.required("role", Schema.oneOf(DayRole.class)),
            fields.required("reason", Schema.oneOf(OverrideReason.class)),
            fields.optional("note", Schema.text().max(240))));

    /**
     * Days shielded from scoring.
     *
     * @param memberId whose days
     * @param range the days, {@code YYYY-MM-DD..YYYY-MM-DD}
     * @param reason why
     * @param note anything written with it
     * @param grantedBy the supervisor granting it
     */
    public record ShieldBody(
            String memberId, String range, ShieldReason reason, Optional<String> note, Optional<String> grantedBy) {}

    public static final Schema<ShieldBody> SHIELD = Schema.object(fields -> new ShieldBody(
            fields.required("memberId", MEMBER_ID),
            fields.required("range", DATE_WINDOW),
            fields.required("reason", Schema.oneOf(ShieldReason.class)),
            fields.optional("note", Schema.text().max(240)),
            fields.optional("grantedBy", MEMBER_ID)));

    /**
     * A query naming a window.
     *
     * @param window the days, {@code YYYY-MM-DD..YYYY-MM-DD}
     */
    public record WindowQuery(String window) {}

    public static final Schema<WindowQuery> WINDOW_QUERY =
            Schema.object(fields -> new WindowQuery(fields.required("window", DATE_WINDOW)));

    /**
     * The parts of a nudge policy to set.
     *
     * @param quietHours {@code HH:MM-HH:MM}
     * @param minGapMinutes the least time between two nudges
     * @param maxPerDay the most nudges in a day
     * @param leadMinutes how long before a deadline to nudge
     * @param roles the day roles nudges are sent on
     * @param channel how they are sent
     */
    public record PolicyBody(
            Optional<String> quietHours,
            OptionalInt minGapMinutes,
            OptionalInt maxPerDay,
            OptionalInt leadMinutes,
            Optional<List<DayRole>> roles,
            Optional<NudgeChannel> channel) {}

    public static final Schema<PolicyBody> POLICY = Schema.object(fields -> new PolicyBody(
            fields.optional("quietHours", Schema.text().matching("\\d{2}:\\d{2}-\\d{2}:\\d{2}", "Invalid")),
            fields.optionalInt("minGapMinutes", Schema.number().integer().min(1).max(1_440)),
            fields.optionalInt("maxPerDay", Schema.number().integer().min(0).max(12)),
            fields.optionalInt("leadMinutes", Schema.number().integer().min(0).max(4_320)),
            fields.optional("roles", Schema.array(Schema.oneOf(DayRole.class), 1, Integer.MAX_VALUE)),
            fields.optional("channel", Schema.oneOf(NudgeChannel.class))));

    /**
     * A sharing change: either the fallback, or one habit's visibility.
     *
     * @param fallback what every habit not named separately shows
     * @param habitId the habit to set apart
     * @param visibility what that habit shows
     */
    public record VisibilityBody(
            Optional<Visibility> fallback, Optional<String> habitId, Optional<Visibility> visibility) {}

    public static final Schema<VisibilityBody> VISIBILITY = Schema.object(fields -> new VisibilityBody(
            fields.optional("fallback", Schema.oneOf(Visibility.class)),
            fields.optional("habitId", HABIT_ID),
            fields.optional("visibility", Schema.oneOf(Visibility.class))));

    /**
     * A new crew.
     *
     * @param name what it is called
     * @param site where it works
     * @param siteZone the site's time zone
     */
    public record CreateCrewBody(String name, String site, String siteZone) {}

    public static final Schema<CreateCrewBody> CREATE_CREW = Schema.object(fields -> new CreateCrewBody(
            fields.required("name", Schema.text().min(2).max(60)),
            fields.required("site", Schema.text().min(2).max(40)),
            fields.required("siteZone", Schema.text().min(3).max(60))));

    /**
     * Somebody joining a crew.
     *
     * @param memberId who
     * @param from the first day they are in it
     * @param role what they do in it
     */
    public record EnrolBody(String memberId, String from, Optional<CrewRole> role) {}

    public static final Schema<EnrolBody> ENROL = Schema.object(fields -> new EnrolBody(
            fields.required("memberId", MEMBER_ID),
            fields.required("from", ISO_DATE),
            fields.optional("role", Schema.oneOf(CrewRole.class))));

    /**
     * Reads a body or a query against {@code schema}, turning a shape problem into a validation
     * failure whose message names the first problem and whose details list every one.
     *
     * @param value the value, or null when the request carried none
     */
    public static <T> T parseBody(Schema<T> schema, JsonNode value) {
        List<ErrorDetail> problems = new ArrayList<>();
        Optional<T> read;
        if (value == null || value instanceof MissingNode) {
            problems.add(new ErrorDetail("", "Required"));
            read = Optional.empty();
        } else {
            read = schema.read(value, "", problems);
        }
        if (problems.isEmpty() && read.isPresent()) {
            return read.get();
        }
        String summary = problems.isEmpty()
                ? "is not valid"
                : Texts.trim(problems.get(0).path() + " " + problems.get(0).message());
        throw new ValidationException(summary, problems);
    }
}
