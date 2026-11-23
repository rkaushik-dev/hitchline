package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A repeating cycle of day roles. The pattern says nothing about when it starts, which is what
 * makes it shareable between everybody on the same rota. Neighbouring segments of one role are
 * always collapsed into one, so two patterns with the same cycle are equal.
 */
public final class RotationPattern {

    private static final Map<String, DayRole> ROLE_WORDS = Map.of(
            "on", DayRole.ONSHIFT,
            "onshift", DayRole.ONSHIFT,
            "off", DayRole.OFFSHIFT,
            "offshift", DayRole.OFFSHIFT,
            "travel", DayRole.TRAVEL,
            "t", DayRole.TRAVEL,
            "standby", DayRole.STANDBY,
            "sb", DayRole.STANDBY);

    private static final Pattern SEGMENT = Pattern.compile("(\\d+)" + Texts.SPACE_CLASS + "*([a-z]+)");
    private static final Pattern SHORTHAND = Pattern.compile("(\\d+)/(\\d+)");
    private static final Pattern TOKEN_GAP = Pattern.compile("(?:" + Texts.SPACE_CLASS + "|,)+");
    private static final int MAX_CYCLE_DAYS = 366;

    private final List<RotationSegment> segments;
    private final List<DayRole> cycle;
    private final DayRole[] roles;

    private RotationPattern(List<RotationSegment> segments, List<DayRole> cycle) {
        this.segments = List.copyOf(segments);
        this.cycle = List.copyOf(cycle);
        this.roles = cycle.toArray(DayRole[]::new);
    }

    /** Builds a pattern from segments, collapsing neighbours of one role. */
    public static RotationPattern of(List<RotationSegment> segments) {
        if (segments.isEmpty()) {
            throw ValidationException.field("pattern", "needs at least one segment");
        }
        for (RotationSegment segment : segments) {
            if (segment.days() < 1) {
                throw ValidationException.field("pattern", "has a segment of " + segment.days() + " days");
            }
        }
        List<RotationSegment> collapsed = collapse(segments);
        long total = collapsed.stream().mapToLong(RotationSegment::days).sum();
        if (total > MAX_CYCLE_DAYS) {
            throw ValidationException.field("pattern", "runs for " + total + " days, longer than a year");
        }
        List<DayRole> cycle = expand(collapsed);
        if (!cycle.contains(DayRole.ONSHIFT)) {
            throw ValidationException.field("pattern", "has no on shift days at all");
        }
        return new RotationPattern(collapsed, cycle);
    }

    public static RotationPattern of(RotationSegment... segments) {
        return of(Arrays.asList(segments));
    }

    private static List<DayRole> expand(List<RotationSegment> segments) {
        List<DayRole> cycle = new ArrayList<>();
        for (RotationSegment segment : segments) {
            for (int day = 0; day < segment.days(); day += 1) {
                cycle.add(segment.role());
            }
        }
        return cycle;
    }

    private static List<RotationSegment> collapse(List<RotationSegment> segments) {
        List<RotationSegment> collapsed = new ArrayList<>();
        for (RotationSegment segment : segments) {
            if (!collapsed.isEmpty()) {
                RotationSegment last = collapsed.get(collapsed.size() - 1);
                if (last.role() == segment.role()) {
                    collapsed.set(collapsed.size() - 1, new RotationSegment(last.role(), last.days() + segment.days()));
                    continue;
                }
            }
            collapsed.add(segment);
        }
        return collapsed;
    }

    private static RotationSegment parseSegment(String token) {
        Matcher match = SEGMENT.matcher(Texts.trim(token).toLowerCase(Locale.ROOT));
        if (!match.matches()) {
            throw ValidationException.field("pattern", "cannot read the segment " + token);
        }
        String word = match.group(2);
        DayRole role = ROLE_WORDS.get(word);
        if (role == null) {
            throw ValidationException.field("pattern", "does not know the day role " + word);
        }
        return new RotationSegment(role, days(match.group(1)));
    }

    /** Reads a run of digits; one too long for a day count reads as a count over a year. */
    private static int days(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException tooLong) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Reads the ways people actually write a rota down: "14/14" for a fortnight each way, the name
     * of a rota such as "2-2-3" or "DuPont", or an explicit run of segments such as "1travel 13on
     * 1travel 13off".
     */
    public static RotationPattern parse(String text) {
        String trimmed = Texts.trim(text).toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            throw ValidationException.field("pattern", "is empty");
        }
        Optional<NamedRota> named = NamedRota.named(trimmed);
        if (named.isPresent()) {
            return named.get().pattern();
        }
        Matcher shorthand = SHORTHAND.matcher(trimmed);
        if (shorthand.matches()) {
            return of(
                    new RotationSegment(DayRole.ONSHIFT, days(shorthand.group(1))),
                    new RotationSegment(DayRole.OFFSHIFT, days(shorthand.group(2))));
        }
        List<RotationSegment> parsed = TOKEN_GAP
                .splitAsStream(trimmed)
                .filter(token -> !token.isEmpty())
                .map(RotationPattern::parseSegment)
                .toList();
        return of(parsed);
    }

    /** The canonical form, such as {@code 1travel 13on 1travel 13off}. */
    public String format() {
        return String.join(
                " ",
                segments.stream()
                        .map(segment -> segment.days() + shortRole(segment.role()))
                        .toList());
    }

    private static String shortRole(DayRole role) {
        return switch (role) {
            case ONSHIFT -> "on";
            case OFFSHIFT -> "off";
            case TRAVEL -> "travel";
            case STANDBY -> "standby";
        };
    }

    /** The name a crewing office would give this cycle, when it is one of the named rotas. */
    public Optional<String> knownAs() {
        return NamedRota.matching(this).map(NamedRota::label);
    }

    public List<RotationSegment> segments() {
        return segments;
    }

    /** The role of every day of one cycle, in order. */
    public List<DayRole> cycle() {
        return cycle;
    }

    public int cycleLength() {
        return roles.length;
    }

    /** The role at a position in the cycle, wrapping in either direction. */
    public DayRole roleAtIndex(long index) {
        return roles[(int) Math.floorMod(index, (long) roles.length)];
    }

    public int countRole(DayRole role) {
        int count = 0;
        for (DayRole entry : roles) {
            if (entry == role) {
                count += 1;
            }
        }
        return count;
    }

    /** Share of the cycle spent on shift, which is what a crewing plan is costed on. */
    public double dutyFraction() {
        return countRole(DayRole.ONSHIFT) / (double) cycleLength();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RotationPattern pattern && pattern.segments.equals(segments);
    }

    @Override
    public int hashCode() {
        return segments.hashCode();
    }

    @Override
    public String toString() {
        return format();
    }
}
