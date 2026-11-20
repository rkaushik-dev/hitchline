package io.github.rkaushikdev.hitchline.domain;

import java.util.Arrays;
import java.util.Optional;

/** The kinds of record that carry an id, each with the prefix its ids start with. */
public enum IdKind {
    CREW_MEMBER("CrewMemberId", "mem"),
    CREW("CrewId", "crw"),
    HABIT("HabitId", "hab"),
    CHECK_IN("CheckInId", "chk");

    private final String label;
    private final String prefix;

    IdKind(String label, String prefix) {
        this.label = label;
        this.prefix = prefix;
    }

    /** The name a validation failure uses for an id of this kind, such as {@code HabitId}. */
    public String label() {
        return label;
    }

    /** The three letters before the underscore. */
    public String prefix() {
        return prefix;
    }

    /** The kind whose label is {@code label}, if there is one. */
    public static Optional<IdKind> fromLabel(String label) {
        return Arrays.stream(values()).filter(kind -> kind.label.equals(label)).findFirst();
    }
}
