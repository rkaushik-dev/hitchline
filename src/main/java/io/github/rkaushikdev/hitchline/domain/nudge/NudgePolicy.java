package io.github.rkaushikdev.hitchline.domain.nudge;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.time.ClockRange;
import io.github.rkaushikdev.hitchline.domain.time.ClockTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * When a person may be pinged and when they may not. Rest hours on a platform are not a
 * preference, they are a rule the operator is audited on, so a nudge that lands inside them is a
 * defect rather than an annoyance.
 *
 * @param quietHours when no nudge may land
 * @param minGapMinutes the least time between two nudges on one day, 1 to 1440
 * @param maxPerDay the most nudges on one day, 0 to 12
 * @param leadMinutes how long before a window closes the first nudge goes out, 0 to 4320
 * @param roles the day roles nudges go out on, deduplicated and in the usual role order
 * @param channel how they go out
 */
public record NudgePolicy(
        ClockRange quietHours,
        int minGapMinutes,
        int maxPerDay,
        int leadMinutes,
        List<DayRole> roles,
        NudgeChannel channel) {

    static final int MAX_PER_DAY_LIMIT = 12;
    static final int MAX_GAP_MINUTES = 24 * 60;
    static final int MAX_LEAD_MINUTES = 72 * 60;

    /** Quiet from 22:00 to 06:00, three a day at least three hours apart, never on a travel day. */
    public static final NudgePolicy DEFAULT_POLICY = new NudgePolicy(
            ClockRange.parse("22:00-06:00"),
            180,
            3,
            240,
            List.of(DayRole.ONSHIFT, DayRole.OFFSHIFT, DayRole.STANDBY),
            NudgeChannel.PUSH);

    public NudgePolicy {
        Objects.requireNonNull(quietHours, "quietHours");
        Objects.requireNonNull(channel, "channel");
        checkNumbers(minGapMinutes, maxPerDay, leadMinutes);
        if (roles.isEmpty()) {
            throw ValidationException.field("roles", "must name at least one day role");
        }
        List<DayRole> ordered = new ArrayList<>(new LinkedHashSet<>(roles));
        ordered.sort(Comparator.comparingInt(DayRole.ALL::indexOf));
        roles = List.copyOf(ordered);
    }

    private static void checkNumbers(int minGapMinutes, int maxPerDay, int leadMinutes) {
        if (minGapMinutes < 1 || minGapMinutes > MAX_GAP_MINUTES) {
            throw ValidationException.field("minGapMinutes", "must be between 1 and " + MAX_GAP_MINUTES);
        }
        if (maxPerDay < 0 || maxPerDay > MAX_PER_DAY_LIMIT) {
            throw ValidationException.field("maxPerDay", "must be between 0 and " + MAX_PER_DAY_LIMIT);
        }
        if (leadMinutes < 0 || leadMinutes > MAX_LEAD_MINUTES) {
            throw ValidationException.field("leadMinutes", "must be between 0 and " + MAX_LEAD_MINUTES);
        }
    }

    /** Starts from the default policy; every part left alone keeps its default. */
    public static Builder builder() {
        return new Builder();
    }

    public boolean isQuiet(ClockTime time) {
        return quietHours.contains(time);
    }

    public boolean nudgesOnRole(DayRole role) {
        return roles.contains(role);
    }

    /** The first minute at or after {@code time} that is not inside quiet hours. */
    public ClockTime firstAllowedTime(ClockTime time) {
        return isQuiet(time) ? quietHours.to() : time;
    }

    /** How many minutes are lost to quiet hours if a nudge is deferred from {@code time}. */
    public int quietMinutesAhead(ClockTime time) {
        if (!isQuiet(time)) {
            return 0;
        }
        int end = quietHours.to().minutesSinceMidnight();
        int now = time.minutesSinceMidnight();
        return end > now ? end - now : ClockTime.MINUTES_IN_DAY - now + end;
    }

    /** True when nothing would ever be sent: no channel, or a daily cap of zero. */
    public boolean isSilent() {
        return channel == NudgeChannel.NONE || maxPerDay == 0;
    }

    public NudgePolicy withChannel(NudgeChannel other) {
        return new NudgePolicy(quietHours, minGapMinutes, maxPerDay, leadMinutes, roles, other);
    }

    /** One line, such as {@code 3 a day by push, quiet 22:00-06:00 overnight}. */
    public String describe() {
        String wraps = quietHours.wrapsMidnight() ? " overnight" : "";
        return maxPerDay + " a day by " + channel + ", quiet " + quietHours + wraps;
    }

    /** {@code time} moved by {@code minutes}, wrapping at midnight. */
    public static ClockTime shiftedBy(ClockTime time, int minutes) {
        return time.plusMinutes(minutes);
    }

    /** The parts of a policy as they arrive from a form, checked when {@link #build} runs. */
    public static final class Builder {

        private String quietHours;
        private int minGapMinutes = DEFAULT_POLICY.minGapMinutes;
        private int maxPerDay = DEFAULT_POLICY.maxPerDay;
        private int leadMinutes = DEFAULT_POLICY.leadMinutes;
        private List<String> roles;
        private NudgeChannel channel = DEFAULT_POLICY.channel;

        private Builder() {}

        public Builder quietHours(String value) {
            quietHours = value;
            return this;
        }

        public Builder minGapMinutes(int value) {
            minGapMinutes = value;
            return this;
        }

        public Builder maxPerDay(int value) {
            maxPerDay = value;
            return this;
        }

        public Builder leadMinutes(int value) {
            leadMinutes = value;
            return this;
        }

        public Builder roles(List<String> value) {
            roles = List.copyOf(value);
            return this;
        }

        public Builder channel(NudgeChannel value) {
            channel = value;
            return this;
        }

        /** Checks the numbers, then the roles, then the quiet hours. */
        public NudgePolicy build() {
            checkNumbers(minGapMinutes, maxPerDay, leadMinutes);
            List<DayRole> named = roles == null
                    ? DEFAULT_POLICY.roles
                    : roles.stream()
                            .map(word -> DayRole.fromWire(word)
                                    .orElseThrow(() ->
                                            ValidationException.field("roles", "does not name a day role: " + word)))
                            .toList();
            if (named.isEmpty()) {
                throw ValidationException.field("roles", "must name at least one day role");
            }
            ClockRange quiet = quietHours == null ? DEFAULT_POLICY.quietHours : ClockRange.parse(quietHours);
            return new NudgePolicy(quiet, minGapMinutes, maxPerDay, leadMinutes, named, channel);
        }
    }
}
