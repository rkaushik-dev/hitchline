package io.github.rkaushikdev.hitchline.domain.checkin;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Numbers;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Pattern;

/**
 * Somebody saying they did the thing. A check in is never deleted: one that turns out to be wrong
 * is voided and stays in the ledger. New check ins are made with {@link #builder}, which applies
 * the rules a fresh report has to meet; the canonical constructor takes a stored row as it is.
 *
 * @param id the check in's id
 * @param habitId the habit it counts towards
 * @param memberId whose it is
 * @param at when the thing was done
 * @param recordedAt when we were told about it
 * @param reading the amount, when the habit measures one
 * @param note anything the person wrote
 * @param source how it reached us
 * @param idempotencyKey the key a handset sent so a retry is not counted twice
 * @param voidedAt when it was voided, if it was
 */
public record CheckIn(
        String id,
        String habitId,
        String memberId,
        Instant at,
        Instant recordedAt,
        OptionalDouble reading,
        String note,
        CheckInSource source,
        Optional<String> idempotencyKey,
        Optional<Instant> voidedAt) {

    /** How far ahead of us a handset clock may be before we call it wrong. */
    public static final long CLOCK_SLACK_MS = 5 * 60_000L;

    /** Event time first, then id. */
    public static final Comparator<CheckIn> ORDER = CheckIn::compare;

    static final int NOTE_MAX = 500;
    static final double MAX_READING = 100_000;

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.:-]{8,64}");

    public CheckIn {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(habitId, "habitId");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(reading, "reading");
        Objects.requireNonNull(note, "note");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(voidedAt, "voidedAt");
    }

    /** Starts a new check in with no reading, no note and no idempotency key. */
    public static Builder builder(
            String id, String habitId, String memberId, Instant at, Instant recordedAt, CheckInSource source) {
        return new Builder(id, habitId, memberId, at, recordedAt, source);
    }

    public boolean isVoided() {
        return voidedAt.isPresent();
    }

    /** The entry voided at {@code when}; one already voided comes back unchanged, the same object. */
    public CheckIn voidAt(Instant when) {
        if (isVoided()) {
            return this;
        }
        if (when.isBefore(recordedAt)) {
            throw ValidationException.field("at", "cannot void a check in before it was recorded");
        }
        return new CheckIn(
                id, habitId, memberId, at, recordedAt, reading, note, source, idempotencyKey, Optional.of(when));
    }

    /** Replaces the reading, or clears it with an empty one. A voided entry cannot be amended. */
    public CheckIn amendReading(OptionalDouble newReading) {
        if (isVoided()) {
            throw ValidationException.field("reading", "cannot be amended on a voided check in");
        }
        if (newReading.isPresent() && isNegativeOrNotFinite(newReading.getAsDouble())) {
            throw negative(newReading.getAsDouble());
        }
        return withReading(newReading);
    }

    public CheckIn withReading(OptionalDouble newReading) {
        return new CheckIn(id, habitId, memberId, at, recordedAt, newReading, note, source, idempotencyKey, voidedAt);
    }

    public CheckIn withNote(String newNote) {
        return new CheckIn(id, habitId, memberId, at, recordedAt, reading, newNote, source, idempotencyKey, voidedAt);
    }

    public CheckIn withSource(CheckInSource newSource) {
        return new CheckIn(id, habitId, memberId, at, recordedAt, reading, note, newSource, idempotencyKey, voidedAt);
    }

    /** How long after the fact we were told, in whole minutes, never negative. */
    public long reportingDelayMinutes() {
        double minutes = (recordedAt.toEpochMilli() - at.toEpochMilli()) / 60_000.0;
        return Math.max(0, Numbers.round(minutes));
    }

    /** True when we were told more than a day after the fact. */
    public boolean isBackfilled() {
        return isBackfilled(24 * 60);
    }

    public boolean isBackfilled(long thresholdMinutes) {
        return reportingDelayMinutes() > thresholdMinutes;
    }

    /** Orders by event time, then by id, answering -1, 0 or 1 on time and the id order on a tie. */
    public static int compare(CheckIn left, CheckIn right) {
        int byTime = left.at.compareTo(right.at);
        if (byTime != 0) {
            return byTime < 0 ? -1 : 1;
        }
        return left.id.compareTo(right.id);
    }

    private static boolean isNegativeOrNotFinite(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) || value < 0;
    }

    private static ValidationException negative(double value) {
        return ValidationException.field("reading", "must not be negative, got " + Numbers.format(value));
    }

    /** The optional parts of a new check in, checked when {@link #build} runs. */
    public static final class Builder {

        private final String id;
        private final String habitId;
        private final String memberId;
        private final Instant at;
        private final Instant recordedAt;
        private final CheckInSource source;
        private OptionalDouble reading = OptionalDouble.empty();
        private String note = "";
        private Optional<String> idempotencyKey = Optional.empty();

        private Builder(
                String id, String habitId, String memberId, Instant at, Instant recordedAt, CheckInSource source) {
            this.id = id;
            this.habitId = habitId;
            this.memberId = memberId;
            this.at = at;
            this.recordedAt = recordedAt;
            this.source = source;
        }

        public Builder reading(double value) {
            reading = OptionalDouble.of(value);
            return this;
        }

        public Builder reading(OptionalDouble value) {
            reading = value;
            return this;
        }

        public Builder note(String value) {
            note = value == null ? "" : value;
            return this;
        }

        public Builder idempotencyKey(String value) {
            idempotencyKey = Optional.of(value);
            return this;
        }

        public Builder idempotencyKey(Optional<String> value) {
            idempotencyKey = value;
            return this;
        }

        /**
         * Checks the report: told no more than five minutes before it happened, a note of at most
         * 500 characters once trimmed, a reading from 0 to 100000, and a key of 8 to 64 plain
         * characters.
         */
        public CheckIn build() {
            if (recordedAt.toEpochMilli() + CLOCK_SLACK_MS < at.toEpochMilli()) {
                throw ValidationException.field(
                        "recordedAt", "is before the check in happened, " + Zones.formatInstant(at));
            }
            String trimmed = Texts.trim(note);
            if (trimmed.length() > NOTE_MAX) {
                throw ValidationException.field("note", "must be at most " + NOTE_MAX + " characters");
            }
            if (reading.isPresent()) {
                double value = reading.getAsDouble();
                if (isNegativeOrNotFinite(value)) {
                    throw negative(value);
                }
                if (value > MAX_READING) {
                    throw ValidationException.field("reading", "must be at most " + Numbers.format(MAX_READING));
                }
            }
            if (idempotencyKey.isPresent() && !KEY.matcher(idempotencyKey.get()).matches()) {
                throw ValidationException.field("idempotencyKey", "must be 8 to 64 plain characters");
            }
            return new CheckIn(
                    id, habitId, memberId, at, recordedAt, reading, trimmed, source, idempotencyKey, Optional.empty());
        }
    }
}
