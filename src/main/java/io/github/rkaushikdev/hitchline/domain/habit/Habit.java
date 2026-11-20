package io.github.rkaushikdev.hitchline.domain.habit;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Something a crew member has said they will do, and how often. The name is tidied, the tags are
 * lower cased, deduplicated and sorted, and the grace window is 0 to 7 days.
 *
 * @param id the habit's id
 * @param memberId whose habit it is
 * @param name what it is called, 3 to 100 characters
 * @param cadence how often it is owed
 * @param target what counts as having done it, when turning up is not enough
 * @param status whether it is asking for anything
 * @param startsOn the first day it asks
 * @param endsOn the last day it asks, when it ends
 * @param tags up to eight short lower case labels
 * @param createdAt when it was made
 * @param graceDays days after the day it was owed on that a check in still counts
 */
public record Habit(
        String id,
        String memberId,
        String name,
        Cadence cadence,
        Optional<HabitTarget> target,
        HabitStatus status,
        CivilDate startsOn,
        Optional<CivilDate> endsOn,
        List<String> tags,
        Instant createdAt,
        int graceDays) {

    static final int NAME_MIN = 3;
    static final int NAME_MAX = 100;
    static final int MAX_TAGS = 8;
    static final int MAX_GRACE_DAYS = 7;

    private static final Pattern TAG = Pattern.compile("[a-z0-9][a-z0-9-]{0,23}");
    private static final Map<HabitStatus, List<HabitStatus>> ALLOWED_MOVES = Map.of(
            HabitStatus.ACTIVE, List.of(HabitStatus.PAUSED, HabitStatus.ARCHIVED),
            HabitStatus.PAUSED, List.of(HabitStatus.ACTIVE, HabitStatus.ARCHIVED),
            HabitStatus.ARCHIVED, List.of());

    public Habit {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(cadence, "cadence");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startsOn, "startsOn");
        Objects.requireNonNull(endsOn, "endsOn");
        Objects.requireNonNull(createdAt, "createdAt");
        if (endsOn.isPresent() && endsOn.get().isBefore(startsOn)) {
            throw ValidationException.field("endsOn", "is before the habit starts");
        }
        if (graceDays < 0 || graceDays > MAX_GRACE_DAYS) {
            throw ValidationException.field("graceDays", "must be between 0 and " + MAX_GRACE_DAYS);
        }
        name = checkName(name);
        tags = checkTags(tags);
    }

    /** Starts a new active habit with no target, no end, no tags and no grace. */
    public static Builder builder(
            String id, String memberId, String name, Cadence cadence, CivilDate startsOn, Instant createdAt) {
        return new Builder(id, memberId, name, cadence, startsOn, createdAt);
    }

    /** Squeezes white space and checks the length. */
    public static String checkName(String raw) {
        String name = Texts.squeeze(raw);
        if (name.length() < NAME_MIN) {
            throw ValidationException.field("name", "must be at least " + NAME_MIN + " characters");
        }
        if (name.length() > NAME_MAX) {
            throw ValidationException.field("name", "must be at most " + NAME_MAX + " characters");
        }
        return name;
    }

    /** Lower cases, deduplicates, checks and sorts a set of tags. */
    public static List<String> checkTags(List<String> raw) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String tag : raw) {
            unique.add(Texts.trim(tag).toLowerCase(Locale.ROOT));
        }
        if (unique.size() > MAX_TAGS) {
            throw ValidationException.field("tags", "must be at most " + MAX_TAGS + " of them");
        }
        for (String tag : unique) {
            if (!TAG.matcher(tag).matches()) {
                throw ValidationException.field("tags", "has an unusable tag: " + tag);
            }
        }
        List<String> sorted = new ArrayList<>(unique);
        sorted.sort(null);
        return List.copyOf(sorted);
    }

    /** True when the habit is meant to be asking for something on that date. */
    public boolean isRunningOn(CivilDate date) {
        if (status != HabitStatus.ACTIVE || date.isBefore(startsOn)) {
            return false;
        }
        return endsOn.map(end -> date.isWithin(startsOn, end)).orElse(true);
    }

    /**
     * Moves the habit to another status. Active and paused habits may move either way or be
     * archived; nothing comes back from the archive. A move to the status it already has changes
     * nothing and hands back this habit.
     */
    public Habit moveTo(HabitStatus next) {
        if (status == next) {
            return this;
        }
        if (!ALLOWED_MOVES.get(status).contains(next)) {
            throw ValidationException.field("status", "cannot go from " + status + " to " + next);
        }
        return new Habit(id, memberId, name, cadence, target, next, startsOn, endsOn, tags, createdAt, graceDays);
    }

    public Habit rename(String newName) {
        return new Habit(id, memberId, newName, cadence, target, status, startsOn, endsOn, tags, createdAt, graceDays);
    }

    /** Sets or, with an empty target, clears what counts as done. */
    public Habit retarget(Optional<HabitTarget> newTarget) {
        if (status == HabitStatus.ARCHIVED) {
            throw ValidationException.field("target", "cannot be changed on an archived habit");
        }
        return new Habit(id, memberId, name, cadence, newTarget, status, startsOn, endsOn, tags, createdAt, graceDays);
    }

    public Habit recadence(Cadence newCadence) {
        if (status == HabitStatus.ARCHIVED) {
            throw ValidationException.field("cadence", "cannot be changed on an archived habit");
        }
        return new Habit(id, memberId, name, newCadence, target, status, startsOn, endsOn, tags, createdAt, graceDays);
    }

    public Habit retag(List<String> newTags) {
        return new Habit(id, memberId, name, cadence, target, status, startsOn, endsOn, newTags, createdAt, graceDays);
    }

    /** The same habit scored against another cadence, target and grace window. */
    public Habit withTerms(Cadence newCadence, Optional<HabitTarget> newTarget, int newGraceDays) {
        return new Habit(
                id, memberId, name, newCadence, newTarget, status, startsOn, endsOn, tags, createdAt, newGraceDays);
    }

    public boolean hasTag(String tag) {
        return tags.contains(Texts.trim(tag).toLowerCase(Locale.ROOT));
    }

    /** One line, such as {@code Twenty minutes 1/onshift-day, at least 20 minutes}. */
    public String describe() {
        String written = target.map(value -> ", " + value.format()).orElse("");
        return name + " " + cadence.format() + written;
    }

    /** The optional parts of a new habit. */
    public static final class Builder {

        private final String id;
        private final String memberId;
        private final String name;
        private final Cadence cadence;
        private final CivilDate startsOn;
        private final Instant createdAt;
        private Optional<HabitTarget> target = Optional.empty();
        private HabitStatus status = HabitStatus.ACTIVE;
        private Optional<CivilDate> endsOn = Optional.empty();
        private List<String> tags = List.of();
        private int graceDays;

        private Builder(
                String id, String memberId, String name, Cadence cadence, CivilDate startsOn, Instant createdAt) {
            this.id = id;
            this.memberId = memberId;
            this.name = name;
            this.cadence = cadence;
            this.startsOn = startsOn;
            this.createdAt = createdAt;
        }

        public Builder target(HabitTarget value) {
            target = Optional.of(value);
            return this;
        }

        public Builder target(Optional<HabitTarget> value) {
            target = value;
            return this;
        }

        public Builder status(HabitStatus value) {
            status = value;
            return this;
        }

        public Builder endsOn(CivilDate value) {
            endsOn = Optional.of(value);
            return this;
        }

        public Builder endsOn(Optional<CivilDate> value) {
            endsOn = value;
            return this;
        }

        public Builder tags(List<String> value) {
            tags = value;
            return this;
        }

        public Builder graceDays(int value) {
            graceDays = value;
            return this;
        }

        public Habit build() {
            return new Habit(id, memberId, name, cadence, target, status, startsOn, endsOn, tags, createdAt, graceDays);
        }
    }
}
