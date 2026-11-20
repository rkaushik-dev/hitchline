package io.github.rkaushikdev.hitchline.app;

import java.time.Instant;
import java.util.Optional;

/**
 * One line of the audit trail: an event and its place in the log.
 *
 * @param sequence one for the first event recorded, then one more for each
 * @param event what happened
 */
public record AuditEntry(long sequence, DomainEvent event) {

    public EventName name() {
        return event.name();
    }

    public Instant at() {
        return event.at();
    }

    public String memberId() {
        return event.memberId();
    }

    public Optional<String> habitId() {
        return event.habitId();
    }

    public Optional<String> checkInId() {
        return event.checkInId();
    }

    public Optional<String> crewId() {
        return event.crewId();
    }

    public String detail() {
        return event.detail();
    }
}
