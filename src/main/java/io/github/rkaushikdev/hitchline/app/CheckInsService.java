package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.IdKind;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.TargetUnit;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationDay;
import io.github.rkaushikdev.hitchline.domain.rotation.Zoning;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Numbers;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Recording, voiding and amending check ins. A check in may not be dated in the future beyond the
 * clock slack, may not be dated more than {@value #BACKFILL_LIMIT_DAYS} days back unless a
 * supervisor or an import sends it, and must fall on a day its habit was running. A habit measured
 * in minutes or hours may not log more on one rotation day than that day is long, which is 23 or
 * 25 hours on the days the clocks change. A repeated idempotency key answers the check in already
 * stored.
 */
public final class CheckInsService {

    /** How far back a check in may be dated. Anything older needs a supervisor. */
    public static final int BACKFILL_LIMIT_DAYS = 14;

    private final Ports ports;

    public CheckInsService(Ports ports) {
        this.ports = ports;
    }

    /**
     * A check in as a handset or form sends it.
     *
     * @param habitId the habit it counts towards
     * @param at when it was done, an ISO 8601 timestamp; now when absent
     * @param reading the amount
     * @param note anything the person wrote
     * @param source how it reached us; the app when absent
     * @param idempotencyKey the key a retry carries
     */
    public record RecordCheckInCommand(
            String habitId,
            Optional<String> at,
            OptionalDouble reading,
            Optional<String> note,
            Optional<CheckInSource> source,
            Optional<String> idempotencyKey) {

        public static RecordCheckInCommand of(String habitId) {
            return new RecordCheckInCommand(
                    habitId,
                    Optional.empty(),
                    OptionalDouble.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        }

        public RecordCheckInCommand withAt(String value) {
            return new RecordCheckInCommand(habitId, Optional.of(value), reading, note, source, idempotencyKey);
        }

        public RecordCheckInCommand withReading(double value) {
            return new RecordCheckInCommand(habitId, at, OptionalDouble.of(value), note, source, idempotencyKey);
        }

        public RecordCheckInCommand withNote(String value) {
            return new RecordCheckInCommand(habitId, at, reading, Optional.of(value), source, idempotencyKey);
        }

        public RecordCheckInCommand withSource(CheckInSource value) {
            return new RecordCheckInCommand(habitId, at, reading, note, Optional.of(value), idempotencyKey);
        }

        public RecordCheckInCommand withIdempotencyKey(String value) {
            return new RecordCheckInCommand(habitId, at, reading, note, source, Optional.of(value));
        }
    }

    /**
     * What recording did.
     *
     * @param entry the check in now standing for the report
     * @param accepted false when an idempotency key had already been used and the old row stands
     */
    public record RecordResult(CheckIn entry, boolean accepted) {}

    private CheckIn loadEntry(String id) {
        return ports.checkIns().byId(id).orElseThrow(() -> new NotFoundException("check in", id));
    }

    public RecordResult record(RecordCheckInCommand command) {
        Habit habit = ports.habits()
                .byId(command.habitId())
                .orElseThrow(() -> new NotFoundException("habit", command.habitId()));
        CrewMember member = ports.members()
                .byId(habit.memberId())
                .orElseThrow(() -> new NotFoundException("crew member", habit.memberId()));

        Instant now = ports.clock().now();
        Instant at = command.at().map(Zones::parseInstant).orElse(now);
        if (at.toEpochMilli() > now.toEpochMilli() + CheckIn.CLOCK_SLACK_MS) {
            throw ValidationException.field("at", "is in the future");
        }
        CivilDate onDate = Zones.civilDateIn(member.siteZone(), at);
        int age = CivilDate.daysBetween(onDate, Zones.civilDateIn(member.siteZone(), now));
        CheckInSource source = command.source().orElse(CheckInSource.APP);
        if (age > BACKFILL_LIMIT_DAYS && source != CheckInSource.SUPERVISOR && source != CheckInSource.IMPORT) {
            throw ValidationException.field(
                    "at", "is " + age + " days back, past the " + BACKFILL_LIMIT_DAYS + " day limit");
        }
        if (!habit.isRunningOn(onDate)) {
            throw ValidationException.field("at", "falls on a day the habit was not running");
        }
        Optional<CheckIn> seen = command.idempotencyKey().flatMap(key -> ports.checkIns().byHabit(habit.id()).stream()
                .filter(entry -> entry.idempotencyKey().equals(Optional.of(key)))
                .findFirst());
        if (seen.isPresent()) {
            return new RecordResult(seen.get(), false);
        }
        if (command.reading().isPresent()) {
            checkDayCeiling(habit, member, at, command.reading().getAsDouble(), Optional.empty());
        }
        CheckIn entry = CheckIn.builder(
                        Ids.checkInId(ports.ids().next(IdKind.CHECK_IN)), habit.id(), member.id(), at, now, source)
                .reading(command.reading())
                .note(command.note().orElse(""))
                .idempotencyKey(command.idempotencyKey())
                .build();
        ports.checkIns().add(entry);
        ports.events()
                .ifPresent(bus -> bus.publish(DomainEvent.of(EventName.CHECKIN_RECORDED, now, member.id())
                        .withHabit(habit.id())
                        .withCheckIn(entry.id())
                        .withDetail(source.wire())));
        return new RecordResult(entry, true);
    }

    public CheckIn voidEntry(String id) {
        return voidEntry(id, Optional.empty());
    }

    /** Voids a check in at {@code at}, or now; one already voided is a conflict. */
    public CheckIn voidEntry(String id, Optional<String> at) {
        CheckIn entry = loadEntry(id);
        if (entry.isVoided()) {
            throw new ConflictException("check in " + id + " is already voided");
        }
        CheckIn voided = entry.voidAt(
                at.map(Zones::parseInstant).orElseGet(() -> ports.clock().now()));
        ports.checkIns().replace(voided);
        ports.events()
                .ifPresent(bus -> bus.publish(
                        DomainEvent.of(EventName.CHECKIN_VOIDED, ports.clock().now(), voided.memberId())
                                .withHabit(voided.habitId())
                                .withCheckIn(voided.id())));
        return voided;
    }

    /** Replaces a reading, held to the same ceiling a new check in is. */
    public CheckIn amend(String id, OptionalDouble reading) {
        CheckIn entry = loadEntry(id);
        CheckIn amended = entry.amendReading(reading);
        if (reading.isPresent()) {
            Optional<Habit> habit = ports.habits().byId(entry.habitId());
            Optional<CrewMember> member = ports.members().byId(entry.memberId());
            if (habit.isPresent() && member.isPresent()) {
                checkDayCeiling(habit.get(), member.get(), entry.at(), reading.getAsDouble(), Optional.of(id));
            }
        }
        ports.checkIns().replace(amended);
        return amended;
    }

    /**
     * Refuses a reading that would take the habit's total for the rotation day past the length of
     * that day on its own clock. {@code replacing} names a check in whose reading is being changed,
     * so its old reading is not counted against the new one. A repeated tap is let through, since
     * it will not be counted, and repeats already stored are left out of the total.
     */
    private void checkDayCeiling(
            Habit habit, CrewMember member, Instant at, double reading, Optional<String> replacing) {
        if (habit.target().isEmpty()) {
            return;
        }
        TargetUnit unit = habit.target().get().unit();
        RotationDay day = Zoning.rotationDayAt(member, at);
        OptionalDouble ceiling = unit.dayCeiling(Zones.dayLengthMinutes(day.zone(), day.date()));
        if (ceiling.isEmpty()) {
            return;
        }
        List<CheckIn> live = ports.checkIns().byHabit(habit.id()).stream()
                .filter(entry -> !entry.isVoided())
                .filter(entry -> replacing.map(id -> !id.equals(entry.id())).orElse(true))
                .toList();
        boolean repeat = replacing.isEmpty()
                && live.stream()
                        .anyMatch(entry ->
                                Math.abs(entry.at().toEpochMilli() - at.toEpochMilli()) <= ResolvedCheckIn.REPEAT_TAP_MS
                                        && entry.reading().equals(OptionalDouble.of(reading)));
        if (repeat) {
            return;
        }
        double already = ResolvedCheckIn.withoutRepeatTaps(ResolvedCheckIn.resolveAll(member, live)).stream()
                .filter(item -> item.day().date().equals(day.date()))
                .mapToDouble(item -> item.checkIn().reading().orElse(0))
                .sum();
        if (unit.round(already + reading) > ceiling.getAsDouble()) {
            throw ValidationException.field(
                    "reading",
                    "takes " + day.date() + " to " + Numbers.format(unit.round(already + reading)) + " " + unit
                            + ", more than the " + Numbers.format(ceiling.getAsDouble()) + " the day holds");
        }
    }

    public List<ResolvedCheckIn> listFor(String memberId) {
        return listFor(memberId, Optional.empty());
    }

    /** The member's live check ins, placed on their rotation days, optionally within a window. */
    public List<ResolvedCheckIn> listFor(String memberId, Optional<DateRange> window) {
        CrewMember member =
                ports.members().byId(memberId).orElseThrow(() -> new NotFoundException("crew member", memberId));
        List<CheckIn> rows = window.isEmpty()
                ? ports.checkIns().byMember(memberId)
                : ports.checkIns().byMember(memberId, window.get());
        List<CheckIn> live = rows.stream().filter(entry -> !entry.isVoided()).toList();
        return ResolvedCheckIn.resolveAll(member, live);
    }
}
