package io.github.rkaushikdev.hitchline.domain.time;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A run of whole days, closed at both ends. Hitches, leave and quota windows are all quoted
 * inclusively by the people who work them, so the type is too.
 *
 * @param from the first day
 * @param to the last day, never before the first
 */
public record DateRange(CivilDate from, CivilDate to) implements Comparable<DateRange> {

    /** Start first, then end. */
    public static final Comparator<DateRange> ORDER = DateRange::compare;

    public DateRange {
        if (from.isAfter(to)) {
            throw ValidationException.field("range", "ends on " + to + " before it starts on " + from);
        }
    }

    public static DateRange of(CivilDate from, CivilDate to) {
        return new DateRange(from, to);
    }

    public static DateRange singleDay(CivilDate date) {
        return new DateRange(date, date);
    }

    /** Reads {@code YYYY-MM-DD..YYYY-MM-DD}. */
    public static DateRange parse(String value) {
        List<String> parts = Texts.split(value, "..");
        if (parts.size() != 2) {
            throw ValidationException.field("range", "must be written as YYYY-MM-DD..YYYY-MM-DD, got " + value);
        }
        return new DateRange(CivilDate.parse(parts.get(0)), CivilDate.parse(parts.get(1)));
    }

    /** The range as {@code YYYY-MM-DD..YYYY-MM-DD}. */
    @Override
    public String toString() {
        return from + ".." + to;
    }

    /** The number of days covered, both ends included. */
    public int length() {
        return CivilDate.dayCount(from, to);
    }

    public boolean contains(CivilDate date) {
        return !date.isBefore(from) && !date.isAfter(to);
    }

    public boolean covers(DateRange inner) {
        return contains(inner.from) && contains(inner.to);
    }

    /** True when the two overlap or one starts the day after the other ends. */
    public boolean touches(DateRange other) {
        return !from.isAfter(other.to.plusDays(1)) && !other.from.isAfter(to.plusDays(1));
    }

    public boolean intersects(DateRange other) {
        return !from.isAfter(other.to) && !other.from.isAfter(to);
    }

    public Optional<DateRange> intersect(DateRange other) {
        if (!intersects(other)) {
            return Optional.empty();
        }
        return Optional.of(new DateRange(CivilDate.max(from, other.from), CivilDate.min(to, other.to)));
    }

    /** Cuts the range down to what fits inside {@code bounds}, or nothing when it misses. */
    public Optional<DateRange> clamp(DateRange bounds) {
        return intersect(bounds);
    }

    public DateRange shift(int days) {
        return new DateRange(from.plusDays(days), to.plusDays(days));
    }

    /** Every day the range covers, in order. */
    public List<CivilDate> days() {
        return CivilDate.eachDay(from, to);
    }

    /** Orders by start then by end, answering exactly -1, 0 or 1. */
    public static int compare(DateRange left, DateRange right) {
        int byStart = CivilDate.compare(left.from, right.from);
        return byStart == 0 ? CivilDate.compare(left.to, right.to) : byStart;
    }

    @Override
    public int compareTo(DateRange other) {
        return compare(this, other);
    }

    /**
     * Sorts and merges. Two ranges that only touch at the join are merged, because a fortnight of
     * leave entered as two weeks is still one absence.
     */
    public static List<DateRange> merge(List<DateRange> ranges) {
        List<DateRange> sorted = new ArrayList<>(ranges);
        sorted.sort(ORDER);
        List<DateRange> merged = new ArrayList<>();
        for (DateRange range : sorted) {
            if (!merged.isEmpty()) {
                DateRange last = merged.get(merged.size() - 1);
                if (last.touches(range)) {
                    merged.set(merged.size() - 1, new DateRange(last.from, CivilDate.max(last.to, range.to)));
                    continue;
                }
            }
            merged.add(range);
        }
        return merged;
    }

    /** Everything in this range that no entry of {@code holes} covers. */
    public List<DateRange> subtract(List<DateRange> holes) {
        List<DateRange> remaining = List.of(this);
        for (DateRange hole : merge(holes)) {
            List<DateRange> next = new ArrayList<>();
            for (DateRange piece : remaining) {
                Optional<DateRange> found = piece.intersect(hole);
                if (found.isEmpty()) {
                    next.add(piece);
                    continue;
                }
                DateRange overlap = found.get();
                if (piece.from.isBefore(overlap.from)) {
                    next.add(new DateRange(piece.from, overlap.from.plusDays(-1)));
                }
                if (piece.to.isAfter(overlap.to)) {
                    next.add(new DateRange(overlap.to.plusDays(1), piece.to));
                }
            }
            remaining = next;
        }
        return List.copyOf(remaining);
    }

    /** Splits into consecutive chunks of {@code size} days, the last one possibly short. */
    public List<DateRange> chunk(int size) {
        if (size < 1) {
            throw ValidationException.field("size", "must be a whole number of days above zero, got " + size);
        }
        List<DateRange> chunks = new ArrayList<>();
        CivilDate cursor = from;
        while (!cursor.isAfter(to)) {
            CivilDate end = CivilDate.min(cursor.plusDays(size - 1L), to);
            chunks.add(new DateRange(cursor, end));
            cursor = end.plusDays(1);
        }
        return chunks;
    }
}
