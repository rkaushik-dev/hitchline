package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A run of days a habit is not held against somebody. A shielded day neither extends a streak nor
 * breaks it, which is the difference between a tool a crew keeps using after a bereavement and one
 * they delete. A shield covers at most 180 days; only sickness and annual leave may be declared by
 * the member, and every other reason records the supervisor who granted it.
 *
 * @param memberId whose days are shielded
 * @param range the days
 * @param reason why
 * @param note anything written with it, at most 240 characters once trimmed
 * @param grantedBy the supervisor who granted it, when it was not self declared
 * @param grantedAt when it was granted
 */
public record Shield(
        String memberId,
        DateRange range,
        ShieldReason reason,
        String note,
        Optional<String> grantedBy,
        Instant grantedAt) {

    static final int MAX_SHIELD_DAYS = 180;
    static final int NOTE_MAX = 240;

    private static final List<ShieldReason> SELF_SERVE = List.of(ShieldReason.SICK, ShieldReason.ANNUAL_LEAVE);

    public Shield {
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(grantedBy, "grantedBy");
        Objects.requireNonNull(grantedAt, "grantedAt");
        int days = range.length();
        if (days > MAX_SHIELD_DAYS) {
            throw ValidationException.field(
                    "range", "covers " + days + " days, more than the " + MAX_SHIELD_DAYS + " allowed");
        }
        note = Texts.trim(note == null ? "" : note);
        if (note.length() > NOTE_MAX) {
            throw ValidationException.field("note", "must be at most " + NOTE_MAX + " characters");
        }
        if (!SELF_SERVE.contains(reason) && grantedBy.isEmpty()) {
            throw ValidationException.field("grantedBy", "is needed to shield days for " + reason);
        }
    }

    /** A self declared shield with no note. */
    public static Shield of(String memberId, DateRange range, ShieldReason reason, Instant grantedAt) {
        return new Shield(memberId, range, reason, "", Optional.empty(), grantedAt);
    }

    public static List<Shield> shieldsFor(List<Shield> shields, String memberId) {
        return shields.stream().filter(entry -> entry.memberId.equals(memberId)).toList();
    }

    public static Optional<Shield> shieldOn(List<Shield> shields, String memberId, CivilDate date) {
        return shields.stream()
                .filter(entry -> entry.memberId.equals(memberId) && entry.range.contains(date))
                .findFirst();
    }

    public static boolean isShielded(List<Shield> shields, String memberId, CivilDate date) {
        return shieldOn(shields, memberId, date).isPresent();
    }

    public static List<Shield> shieldsOver(List<Shield> shields, String memberId, DateRange range) {
        return shieldsFor(shields, memberId).stream()
                .filter(entry -> entry.range.intersects(range))
                .toList();
    }

    /** The distinct days of {@code range} covered, so two overlapping entries are counted once. */
    public static int shieldedDayCount(List<Shield> shields, String memberId, DateRange range) {
        List<DateRange> covering = shieldsOver(shields, memberId, range).stream()
                .map(Shield::range)
                .toList();
        int count = 0;
        for (CivilDate date : range.days()) {
            if (covering.stream().anyMatch(window -> window.contains(date))) {
                count += 1;
            }
        }
        return count;
    }

    /** The member's shielded days as merged runs. */
    public static List<DateRange> coveredRanges(List<Shield> shields, String memberId) {
        return DateRange.merge(
                shieldsFor(shields, memberId).stream().map(Shield::range).toList());
    }

    /** One line, such as {@code annual_leave self declared}. */
    public String describe() {
        return reason + " " + grantedBy.map(who -> "granted by " + who).orElse("self declared");
    }
}
