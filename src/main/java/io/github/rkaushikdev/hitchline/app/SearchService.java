package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitStatus;
import io.github.rkaushikdev.hitchline.util.Collation;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Finding a habit again. A coordinator setting up a rig ends up with a few hundred of them across
 * a crew, and "which of these is the rest hours one" is the question they ask, so the search is
 * over names and tags rather than over ids.
 */
public final class SearchService {

    static final int TEXT_MAX = 80;

    private final Ports ports;

    public SearchService(Ports ports) {
        this.ports = ports;
    }

    /**
     * What to look for. Every part is optional.
     *
     * @param memberId only this member's habits
     * @param text words the name must contain, ignoring case
     * @param tags tags the habit must carry
     * @param status only habits with this status
     * @param allTags match every tag rather than any of them
     */
    public record HabitQuery(
            Optional<String> memberId,
            Optional<String> text,
            List<String> tags,
            Optional<HabitStatus> status,
            boolean allTags) {

        public HabitQuery {
            tags = List.copyOf(tags);
        }

        /** A query that matches everything. */
        public static HabitQuery everything() {
            return new HabitQuery(Optional.empty(), Optional.empty(), List.of(), Optional.empty(), false);
        }

        public HabitQuery forMember(String value) {
            return new HabitQuery(Optional.of(value), text, tags, status, allTags);
        }

        public HabitQuery containing(String value) {
            return new HabitQuery(memberId, Optional.of(value), tags, status, allTags);
        }

        public HabitQuery tagged(List<String> value) {
            return new HabitQuery(memberId, text, value, status, allTags);
        }

        public HabitQuery withStatus(HabitStatus value) {
            return new HabitQuery(memberId, text, tags, Optional.of(value), allTags);
        }

        public HabitQuery matchingAllTags() {
            return new HabitQuery(memberId, text, tags, status, true);
        }
    }

    /**
     * How many habits carry a tag.
     *
     * @param tag the tag
     * @param habits how many habits carry it
     */
    public record TagCount(String tag, int habits) {}

    private static String normalise(String text) {
        return Texts.trim(text).toLowerCase(Locale.ROOT);
    }

    private List<Habit> candidates(Optional<String> memberId) {
        if (memberId.isPresent()) {
            return ports.habits().byMember(memberId.get());
        }
        List<Habit> found = new ArrayList<>();
        for (CrewMember member : ports.members().all()) {
            found.addAll(ports.habits().byMember(member.id()));
        }
        return found;
    }

    private static boolean matchesTags(Habit habit, List<String> tags, boolean all) {
        if (tags.isEmpty()) {
            return true;
        }
        return all ? tags.stream().allMatch(habit::hasTag) : tags.stream().anyMatch(habit::hasTag);
    }

    /** The habits matching the query, by name. */
    public List<Habit> habits(HabitQuery query) {
        Optional<String> text = query.text().map(SearchService::normalise);
        if (text.isPresent() && text.get().length() > TEXT_MAX) {
            throw ValidationException.field("text", "must be at most " + TEXT_MAX + " characters");
        }
        List<String> tags = query.tags().stream()
                .map(SearchService::normalise)
                .filter(tag -> !tag.isEmpty())
                .toList();
        List<Habit> found = new ArrayList<>(candidates(query.memberId()).stream()
                .filter(habit ->
                        query.status().map(status -> habit.status() == status).orElse(true))
                .filter(habit -> text.map(wanted -> normalise(habit.name()).contains(wanted))
                        .orElse(true))
                .filter(habit -> matchesTags(habit, tags, query.allTags()))
                .toList());
        found.sort(Comparator.comparing(Habit::name, Collation.ORDER));
        return found;
    }

    /** Every tag in use, the most used first, then by tag. */
    public List<TagCount> tags(Optional<String> memberId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Habit habit : candidates(memberId)) {
            for (String tag : habit.tags()) {
                counts.merge(tag, 1, Integer::sum);
            }
        }
        List<TagCount> listed = new ArrayList<>();
        counts.forEach((tag, habits) -> listed.add(new TagCount(tag, habits)));
        listed.sort(Comparator.comparingInt(TagCount::habits).reversed().thenComparing(TagCount::tag, Collation.ORDER));
        return listed;
    }
}
