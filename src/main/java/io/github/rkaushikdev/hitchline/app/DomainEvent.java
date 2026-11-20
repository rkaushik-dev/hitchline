package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What happened, said once. Services publish these after the write lands, and anything that wants
 * to react reads them from the bus rather than being called from inside the service that did the
 * work.
 *
 * @param name what happened
 * @param at when
 * @param memberId whose record it touched
 * @param habitId the habit, when one was involved
 * @param checkInId the check in, when one was involved
 * @param crewId the crew, when one was involved
 * @param detail a few words about it, at most 240 characters once trimmed
 */
public record DomainEvent(
        EventName name,
        Instant at,
        String memberId,
        Optional<String> habitId,
        Optional<String> checkInId,
        Optional<String> crewId,
        String detail) {

    static final int DETAIL_MAX = 240;

    public DomainEvent {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(habitId, "habitId");
        Objects.requireNonNull(checkInId, "checkInId");
        Objects.requireNonNull(crewId, "crewId");
        detail = Texts.trim(detail == null ? "" : detail);
        if (detail.length() > DETAIL_MAX) {
            throw ValidationException.field("detail", "must be at most " + DETAIL_MAX + " characters");
        }
    }

    /** An event about a member and nothing more specific. */
    public static DomainEvent of(EventName name, Instant at, String memberId) {
        return new DomainEvent(name, at, memberId, Optional.empty(), Optional.empty(), Optional.empty(), "");
    }

    public DomainEvent withHabit(String id) {
        return new DomainEvent(name, at, memberId, Optional.of(id), checkInId, crewId, detail);
    }

    public DomainEvent withCheckIn(String id) {
        return new DomainEvent(name, at, memberId, habitId, Optional.of(id), crewId, detail);
    }

    public DomainEvent withCrew(String id) {
        return new DomainEvent(name, at, memberId, habitId, checkInId, Optional.of(id), detail);
    }

    public DomainEvent withDetail(String text) {
        return new DomainEvent(name, at, memberId, habitId, checkInId, crewId, text);
    }

    /** One line, naming the most specific subject: the habit, then the check in, the crew, the member. */
    public String describe() {
        String subject = habitId.or(() -> checkInId).or(() -> crewId).orElse(memberId);
        String said = detail.isEmpty() ? "" : " " + detail;
        return Zones.formatInstant(at) + " " + name + " " + subject + said;
    }
}
