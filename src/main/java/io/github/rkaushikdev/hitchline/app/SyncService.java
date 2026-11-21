package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.app.CheckInsService.RecordCheckInCommand;
import io.github.rkaushikdev.hitchline.app.CheckInsService.RecordResult;
import io.github.rkaushikdev.hitchline.domain.DomainException;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * A handset that has been out of signal for a fortnight. Everything it queued arrives at once, in
 * whatever order the phone kept it, and it needs a per item answer so it knows which rows to clear
 * and which to keep trying. The whole batch is never rejected: a rig with no signal is the normal
 * case offshore, and an all or nothing sync would mean one bad row costs a fortnight of records.
 * At most 500 items are taken from one push, and they are recorded oldest first.
 */
public final class SyncService {

    static final int MAX_BATCH = 500;

    private final CheckInsService checkIns;

    public SyncService(Ports ports) {
        this.checkIns = new CheckInsService(ports);
    }

    /** What became of one queued item. */
    public enum SyncOutcome implements WireValue {
        ACCEPTED("accepted"),
        ALREADY_HAD_IT("already_had_it"),
        REFUSED("refused");

        private final String wire;

        SyncOutcome(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }

        @Override
        public String toString() {
            return wire;
        }
    }

    /**
     * A check in a handset queued while out of signal.
     *
     * @param localId the handset's own id for the row, echoed back so it can clear its queue
     * @param habitId the habit
     * @param at when it was done
     * @param reading the amount
     * @param note anything written
     * @param idempotencyKey the key the handset sent, if any
     */
    public record QueuedCheckIn(
            String localId,
            String habitId,
            String at,
            OptionalDouble reading,
            Optional<String> note,
            Optional<String> idempotencyKey) {

        public static QueuedCheckIn of(String localId, String habitId, String at) {
            return new QueuedCheckIn(localId, habitId, at, OptionalDouble.empty(), Optional.empty(), Optional.empty());
        }
    }

    /**
     * The answer for one item.
     *
     * @param localId the handset's id for it
     * @param outcome what became of it
     * @param checkInId the stored check in it stands for, unless it was refused
     * @param message a few words for the handset to show
     */
    public record SyncItemResult(String localId, SyncOutcome outcome, Optional<String> checkInId, String message) {}

    /**
     * The answer for a push.
     *
     * @param memberId whose handset
     * @param results one per item taken, oldest first
     * @param accepted items stored
     * @param duplicates items already stored
     * @param refused items refused
     */
    public record SyncReport(String memberId, List<SyncItemResult> results, int accepted, int duplicates, int refused) {

        public SyncReport {
            results = List.copyOf(results);
        }

        /** The local ids a handset may clear: everything that will not change answer. */
        public List<String> settledIds() {
            return results.stream()
                    .filter(result -> result.outcome() != SyncOutcome.REFUSED)
                    .map(SyncItemResult::localId)
                    .toList();
        }

        /** One line, such as {@code 2 stored, 1 already here, 0 refused}. */
        public String describe() {
            return accepted + " stored, " + duplicates + " already here, " + refused + " refused";
        }
    }

    /** When an item was done, for ordering; an item whose time cannot be read sorts last. */
    private static long momentOf(QueuedCheckIn item) {
        try {
            return Zones.parseInstant(item.at()).toEpochMilli();
        } catch (ValidationException unreadable) {
            return Long.MAX_VALUE;
        }
    }

    public SyncReport push(String memberId, List<QueuedCheckIn> queued) {
        List<QueuedCheckIn> batch = new ArrayList<>(queued.subList(0, Math.min(MAX_BATCH, queued.size())));
        batch.sort(Comparator.comparingLong(SyncService::momentOf));
        List<SyncItemResult> results = new ArrayList<>();
        int accepted = 0;
        int duplicates = 0;
        int refused = 0;
        for (QueuedCheckIn item : batch) {
            try {
                RecordCheckInCommand command = new RecordCheckInCommand(
                        Ids.habitId(item.habitId()),
                        Optional.of(item.at()),
                        item.reading(),
                        item.note(),
                        Optional.of(CheckInSource.APP),
                        // A handset that resends after a dropped connection must not double count,
                        // so its own id stands in when it sent no key.
                        Optional.of(item.idempotencyKey().orElse("queued-" + item.localId())));
                RecordResult result = checkIns.record(command);
                if (result.accepted()) {
                    accepted += 1;
                    results.add(new SyncItemResult(
                            item.localId(),
                            SyncOutcome.ACCEPTED,
                            Optional.of(result.entry().id()),
                            "stored"));
                } else {
                    duplicates += 1;
                    results.add(new SyncItemResult(
                            item.localId(),
                            SyncOutcome.ALREADY_HAD_IT,
                            Optional.of(result.entry().id()),
                            "this one was already here"));
                }
            } catch (DomainException refusal) {
                refused += 1;
                results.add(new SyncItemResult(
                        item.localId(), SyncOutcome.REFUSED, Optional.empty(), refusal.getMessage()));
            }
        }
        return new SyncReport(memberId, results, accepted, duplicates, refused);
    }
}
