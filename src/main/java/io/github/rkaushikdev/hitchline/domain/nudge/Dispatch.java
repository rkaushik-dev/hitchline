package io.github.rkaushikdev.hitchline.domain.nudge;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What happened to a nudge after it was planned. Planning is pure and cheap to repeat; delivery is
 * neither, so the plan and the record of what was actually sent are kept apart and only this half
 * is written down. A planned nudge is sent or dropped; a sent one may be sent again, seen or
 * dropped; seen and dropped are final.
 *
 * @param key the obligation it was planned for
 * @param habitId the habit
 * @param memberId who it is for
 * @param channel how it goes out
 * @param plannedFor when it was meant to go
 * @param state where it is now
 * @param stateAt when it got there
 * @param attempts how many times it was sent
 * @param note why it was dropped, or what the sender said, at most 240 characters
 */
public record Dispatch(
        String key,
        String habitId,
        String memberId,
        NudgeChannel channel,
        Instant plannedFor,
        DispatchState state,
        Instant stateAt,
        int attempts,
        String note) {

    static final int NOTE_MAX = 240;

    private static final Map<DispatchState, List<DispatchState>> MOVES = Map.of(
            DispatchState.PLANNED, List.of(DispatchState.SENT, DispatchState.DROPPED),
            DispatchState.SENT, List.of(DispatchState.SEEN, DispatchState.DROPPED, DispatchState.SENT),
            DispatchState.SEEN, List.of(),
            DispatchState.DROPPED, List.of());

    /** A dispatch record for a nudge just planned. */
    public static Dispatch plan(PlannedNudge nudge, String memberId, NudgeChannel channel, Instant at) {
        return new Dispatch(
                nudge.key(), nudge.habitId(), memberId, channel, nudge.at(), DispatchState.PLANNED, at, 0, "");
    }

    private Dispatch moveTo(DispatchState next, Instant at, String withNote) {
        if (!MOVES.get(state).contains(next)) {
            throw new ConflictException("a nudge cannot go from " + state + " to " + next);
        }
        if (at.isBefore(stateAt)) {
            throw ValidationException.field("at", "is before the last move at " + Zones.formatInstant(stateAt));
        }
        String trimmed = Texts.trim(withNote);
        String kept = trimmed.length() > NOTE_MAX ? trimmed.substring(0, NOTE_MAX) : trimmed;
        int sends = next == DispatchState.SENT ? attempts + 1 : attempts;
        return new Dispatch(key, habitId, memberId, channel, plannedFor, next, at, sends, kept);
    }

    public Dispatch markSent(Instant at) {
        return markSent(at, "");
    }

    public Dispatch markSent(Instant at, String withNote) {
        return moveTo(DispatchState.SENT, at, withNote);
    }

    public Dispatch markSeen(Instant at) {
        return moveTo(DispatchState.SEEN, at, "");
    }

    /** Drops the nudge, which needs a reason. */
    public Dispatch markDropped(Instant at, String why) {
        if (Texts.trim(why).isEmpty()) {
            throw ValidationException.field("why", "is needed when a nudge is dropped");
        }
        return moveTo(DispatchState.DROPPED, at, why);
    }

    public boolean shouldRetry(Instant now) {
        return shouldRetry(now, RetryPolicy.DEFAULT);
    }

    /**
     * Whether a sent nudge that nobody has looked at is worth sending again. A person on shift
     * cannot answer a handset, so the backoff is generous and the attempts are few.
     */
    public boolean shouldRetry(Instant now, RetryPolicy policy) {
        if (state != DispatchState.SENT || attempts >= policy.maxAttempts()) {
            return false;
        }
        return now.toEpochMilli() - stateAt.toEpochMilli() >= policy.backoffMinutes() * 60_000L;
    }

    public boolean isSettled() {
        return state == DispatchState.SEEN || state == DispatchState.DROPPED;
    }

    /** The planned dispatches whose moment has come. */
    public static List<Dispatch> dueNow(List<Dispatch> entries, Instant now) {
        return entries.stream()
                .filter(entry -> entry.state == DispatchState.PLANNED && !entry.plannedFor.isAfter(now))
                .toList();
    }

    /** One line, such as {@code hab_...:day:2025-06-02 dropped by push after 0 (quiet hours)}. */
    public String describe() {
        String said = note.isEmpty() ? "" : " (" + note + ")";
        return key + " " + state + " by " + channel + " after " + attempts + said;
    }
}
