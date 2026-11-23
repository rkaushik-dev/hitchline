package io.github.rkaushikdev.hitchline.domain.rotation;

import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The rotas a crewing office calls by name rather than writing out. Nobody types the DuPont cycle
 * day by day; they say "DuPont" and expect the tool to know it is a 28 day cycle ending in a week
 * off. Each rota answers to every name it goes by, written with spaces or hyphens.
 */
public enum NamedRota {
    /** Two on, two off, three on, then the mirror of it: the classic 14 day swing. */
    PANAMA(List.of("panama", "2-2-3", "pitman"), "2on 2off 3on 2off 2on 3off"),
    /** Four on, three off, three on, one off, three on, three off, four on, then a week off. */
    DUPONT(List.of("dupont"), "4on 3off 3on 1off 3on 3off 4on 7off"),
    /** Four days on and four off, the way many drilling contractors run a rig crew. */
    FOUR_ON_FOUR_OFF(List.of("four-on-four-off", "4-on-4-off"), "4on 4off"),
    /** A fortnight offshore and a fortnight at home. */
    EQUAL_TIME(List.of("equal-time", "two-two"), "14on 14off"),
    /** A fortnight offshore and three weeks at home, the long standing North Sea rota. */
    TWO_THREE(List.of("two-three"), "14on 21off"),
    /** Three weeks offshore and three at home, common on remote sites. */
    THREE_THREE(List.of("three-three"), "21on 21off");

    private final List<String> names;
    private final String segments;

    NamedRota(List<String> names, String segments) {
        this.names = List.copyOf(names);
        this.segments = segments;
    }

    /** Every name the rota answers to, the usual one first. */
    public List<String> names() {
        return names;
    }

    /** The name a report shows. */
    public String label() {
        return names.get(0);
    }

    /** The cycle, written out as segments. */
    public RotationPattern pattern() {
        return RotationPattern.parse(segments);
    }

    /** The rota a name stands for, ignoring case and treating spaces as hyphens. */
    public static Optional<NamedRota> named(String text) {
        String key = Texts.SPACE_RUN
                .matcher(Texts.trim(text).toLowerCase(Locale.ROOT))
                .replaceAll("-");
        return Arrays.stream(values()).filter(rota -> rota.names.contains(key)).findFirst();
    }

    /** The named rota with exactly this cycle, when there is one. */
    public static Optional<NamedRota> matching(RotationPattern pattern) {
        return Arrays.stream(values())
                .filter(rota -> rota.pattern().equals(pattern))
                .findFirst();
    }
}
