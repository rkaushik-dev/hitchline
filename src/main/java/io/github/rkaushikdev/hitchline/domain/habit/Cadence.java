package io.github.rkaushikdev.hitchline.domain.habit;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How often a habit is owed. Four of the five shapes here are anchored to the rotation rather than
 * to the calendar, which is the whole point of the thing: "twice on every shift day" and "three
 * times a hitch" are different promises and neither one is a weekly count.
 */
public sealed interface Cadence {

    int MAX_TIMES = 48;
    int MIN_WINDOW = 2;
    int MAX_WINDOW = 120;

    /** Which of the five shapes this is. */
    CadenceKind kind();

    /** How many times the cadence asks for, from 1 to 48. */
    int times();

    /** A number of times every calendar day. */
    record PerDay(int times) implements Cadence {
        public PerDay {
            checkTimes(times);
        }

        @Override
        public CadenceKind kind() {
            return CadenceKind.PER_DAY;
        }
    }

    /** A number of times on every day whose role is one of {@code roles}. */
    record PerRoleDay(List<DayRole> roles, int times) implements Cadence {
        public PerRoleDay {
            if (roles.isEmpty()) {
                throw ValidationException.field("roles", "must name at least one day role");
            }
            roles = List.copyOf(new LinkedHashSet<>(roles));
            checkTimes(times);
        }

        @Override
        public CadenceKind kind() {
            return CadenceKind.PER_ROLE_DAY;
        }
    }

    /** A number of times per turn of the rotation. */
    record PerHitch(int times) implements Cadence {
        public PerHitch {
            checkTimes(times);
        }

        @Override
        public CadenceKind kind() {
            return CadenceKind.PER_HITCH;
        }
    }

    /** A number of times per unbroken run of days in the named role, however long the run is. */
    record PerBlock(DayRole role, int times) implements Cadence {
        public PerBlock {
            checkTimes(times);
        }

        @Override
        public CadenceKind kind() {
            return CadenceKind.PER_BLOCK;
        }
    }

    /** A number of times in every tile of {@code windowDays} days, from 2 to 120. */
    record PerWindow(int times, int windowDays) implements Cadence {
        public PerWindow {
            if (windowDays < MIN_WINDOW || windowDays > MAX_WINDOW) {
                throw ValidationException.field(
                        "windowDays",
                        "must be between " + MIN_WINDOW + " and " + MAX_WINDOW + " days, got " + windowDays);
            }
            checkTimes(times);
        }

        @Override
        public CadenceKind kind() {
            return CadenceKind.PER_WINDOW;
        }
    }

    private static void checkTimes(int times) {
        if (times < 1) {
            throw ValidationException.field("times", "must be a whole number above zero, got " + times);
        }
        if (times > MAX_TIMES) {
            throw ValidationException.field("times", "must be at most " + MAX_TIMES + ", got " + times);
        }
    }

    static PerDay perDay(int times) {
        return new PerDay(times);
    }

    static PerRoleDay perRoleDay(List<DayRole> roles, int times) {
        return new PerRoleDay(roles, times);
    }

    static PerHitch perHitch(int times) {
        return new PerHitch(times);
    }

    static PerBlock perBlock(DayRole role, int times) {
        return new PerBlock(role, times);
    }

    static PerWindow perWindow(int times, int windowDays) {
        return new PerWindow(times, windowDays);
    }

    /**
     * Reads the short forms people type: "2/day", "1/onshift-day", "1/onshift+standby-day",
     * "3/hitch", "1/onshift-block", "5/14d". Case and surrounding space do not matter, and "on" and
     * "off" stand for the shift roles.
     */
    static Cadence parse(String text) {
        String trimmed = Texts.trim(text).toLowerCase(Locale.ROOT);
        Matcher match = Forms.UNIT.matcher(trimmed);
        if (!match.matches()) {
            throw ValidationException.field("cadence", "must look like 3/hitch, got " + text);
        }
        int times = Forms.count(match.group(1));
        String unit = match.group(2);
        if (unit.equals("day")) {
            return perDay(times);
        }
        if (unit.equals("hitch")) {
            return perHitch(times);
        }
        Matcher window = Forms.WINDOW.matcher(unit);
        if (window.matches()) {
            return perWindow(times, Forms.count(window.group(1)));
        }
        Matcher roleDay = Forms.ROLE_DAY.matcher(unit);
        if (roleDay.matches()) {
            List<DayRole> roles = new ArrayList<>();
            for (String word : Texts.split(roleDay.group(1), "+")) {
                if (!word.isEmpty()) {
                    roles.add(Forms.readRole(word));
                }
            }
            return perRoleDay(roles, times);
        }
        Matcher block = Forms.BLOCK.matcher(unit);
        if (block.matches()) {
            return perBlock(Forms.readRole(block.group(1)), times);
        }
        throw ValidationException.field("cadence", "does not know the unit " + unit);
    }

    /** The short form, with the role words written in full. */
    default String format() {
        return switch (this) {
            case PerDay day -> day.times() + "/day";
            case PerRoleDay roleDay ->
                roleDay.times() + "/"
                        + String.join(
                                "+", roleDay.roles().stream().map(DayRole::wire).toList()) + "-day";
            case PerHitch hitch -> hitch.times() + "/hitch";
            case PerBlock block -> block.times() + "/" + block.role() + "-block";
            case PerWindow window -> window.times() + "/" + window.windowDays() + "d";
        };
    }

    /** True when the cadence only ever asks for something on certain kinds of day. */
    default boolean isRoleBound() {
        return this instanceof PerRoleDay || this instanceof PerBlock;
    }

    /** The roles the cadence reads, in the order it names them. */
    default List<DayRole> rolesTouched() {
        return switch (this) {
            case PerRoleDay roleDay -> roleDay.roles();
            case PerBlock block -> List.of(block.role());
            default -> List.of();
        };
    }

    /** The patterns of the short forms. */
    final class Forms {
        private static final Pattern UNIT = Pattern.compile("(\\d+)/(.+)", Pattern.DOTALL);
        private static final Pattern WINDOW = Pattern.compile("(\\d+)d");
        private static final Pattern ROLE_DAY = Pattern.compile("([a-z+]+)-day");
        private static final Pattern BLOCK = Pattern.compile("([a-z]+)-block");

        private Forms() {}

        /** Reads a run of digits; one too long for a count reads as a count past every limit. */
        static int count(String digits) {
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException tooLong) {
                return Integer.MAX_VALUE;
            }
        }

        static DayRole readRole(String word) {
            String full =
                    switch (word) {
                        case "on" -> "onshift";
                        case "off" -> "offshift";
                        default -> word;
                    };
            return DayRole.fromWire(full)
                    .orElseThrow(() -> ValidationException.field("cadence", "does not name a day role: " + word));
        }
    }
}
