package io.github.rkaushikdev.hitchline.domain.habit;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A habit an operator hands out rather than inventing each time. A rig has a standing set: the
 * safety read through, the rest hours, the exercise the medic asks for. Templates keep those
 * written down once so two crews are measured against the same thing.
 *
 * @param key a short lower case name, 3 to 40 characters
 * @param name what a habit made from it is called
 * @param cadence how often it is owed
 * @param target what counts as done
 * @param tags lower cased, deduplicated and sorted
 * @param graceDays 0 to 7
 * @param note why it is the way it is
 */
public record HabitTemplate(
        String key,
        String name,
        Cadence cadence,
        Optional<HabitTarget> target,
        List<String> tags,
        int graceDays,
        String note) {

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{2,39}");

    /** The set that ships with the tool, which an operator can add to. */
    public static final List<HabitTemplate> STANDARD_TEMPLATES = List.of(
            builder("toolbox-talk", "Read the toolbox talk", "1/onshift-day")
                    .tags("safety")
                    .note("the sheet posted at the start of shift")
                    .build(),
            builder("rest-hours", "Log the rest hours", "1/day")
                    .target("at least 10 hours")
                    .tags("rest", "compliance")
                    .graceDays(1)
                    .note("hours of rest in the day; the rules ask for 10 in any 24 and 77 in any seven days")
                    .build(),
            builder("gym-session", "Twenty minutes in the gym", "3/hitch")
                    .target("at least 20 minutes")
                    .tags("fitness")
                    .build(),
            builder("call-home", "Call home", "1/offshift-block")
                    .tags("wellbeing")
                    .build(),
            builder("kit-check", "Check the survival suit", "1/onshift-block")
                    .tags("safety")
                    .note("once per stretch offshore, not once a day")
                    .build());

    public HabitTemplate {
        tags = List.copyOf(tags);
    }

    /** Starts a template written the way an operator types one: cadence and target as text. */
    public static Builder builder(String key, String name, String cadence) {
        return new Builder(key, name, cadence);
    }

    /** One line, such as {@code gym-session: Twenty minutes in the gym 3/hitch, at least 20 minutes}. */
    public String describe() {
        String written = target.map(value -> ", " + value.format()).orElse("");
        return key + ": " + name + " " + cadence.format() + written;
    }

    /** The standard set plus any extra templates; no two may share a key. */
    public static List<HabitTemplate> library(List<HabitTemplate> extra) {
        List<HabitTemplate> templates = new ArrayList<>(STANDARD_TEMPLATES);
        for (HabitTemplate template : extra) {
            if (templates.stream().anyMatch(held -> held.key.equals(template.key))) {
                throw new ConflictException("a template already uses the key " + template.key);
            }
            templates.add(template);
        }
        return List.copyOf(templates);
    }

    public static List<HabitTemplate> library() {
        return STANDARD_TEMPLATES;
    }

    public static HabitTemplate find(List<HabitTemplate> library, String key) {
        return library.stream()
                .filter(template -> template.key.equals(key))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("habit template", key));
    }

    public static List<HabitTemplate> tagged(List<HabitTemplate> library, String tag) {
        String wanted = Texts.trim(tag).toLowerCase(Locale.ROOT);
        return library.stream()
                .filter(template -> template.tags.contains(wanted))
                .toList();
    }

    /** The parts of a template, written as text and checked when {@link #build} runs. */
    public static final class Builder {

        private final String key;
        private final String name;
        private final String cadence;
        private String target;
        private List<String> tags = List.of();
        private int graceDays;
        private String note = "";

        private Builder(String key, String name, String cadence) {
            this.key = key;
            this.name = name;
            this.cadence = cadence;
        }

        public Builder target(String value) {
            target = value;
            return this;
        }

        public Builder tags(String... value) {
            tags = List.of(value);
            return this;
        }

        public Builder tags(List<String> value) {
            tags = List.copyOf(value);
            return this;
        }

        public Builder graceDays(int value) {
            graceDays = value;
            return this;
        }

        public Builder note(String value) {
            note = value;
            return this;
        }

        public HabitTemplate build() {
            if (!KEY.matcher(key).matches()) {
                throw ValidationException.field("key", "must be a short lower case name, got " + key);
            }
            if (graceDays < 0 || graceDays > Habit.MAX_GRACE_DAYS) {
                throw ValidationException.field("graceDays", "must be between 0 and 7");
            }
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String tag : tags) {
                unique.add(Texts.trim(tag).toLowerCase(Locale.ROOT));
            }
            List<String> sorted = new ArrayList<>(unique);
            sorted.sort(null);
            return new HabitTemplate(
                    key,
                    Texts.trim(name),
                    Cadence.parse(cadence),
                    Optional.ofNullable(target).map(HabitTarget::parse),
                    sorted,
                    graceDays,
                    Texts.trim(note));
        }
    }
}
