package io.github.rkaushikdev.hitchline.support;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.habit.Cadence;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitTarget;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationAssignment;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationPattern;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The default records the tests build on. The default crew member works a fortnight on a
 * Norwegian platform and a fortnight at home in Lisbon, starting on the first Monday of 2025.
 */
public final class Fixtures {

    private Fixtures() {}

    /** What a test may change about the default member. */
    public static final class MemberOptions {
        private String id = "mem_0123456789ABCDEF";
        private String displayName = "Ada Vasquez";
        private String homeZone = "Europe/Lisbon";
        private String siteZone = "Europe/Oslo";
        private String pattern = "14/14";
        private String anchor = "2025-01-06";
        private int phase;
        private String joinedOn = "2024-11-01";

        public MemberOptions id(String value) {
            id = value;
            return this;
        }

        public MemberOptions displayName(String value) {
            displayName = value;
            return this;
        }

        public MemberOptions homeZone(String value) {
            homeZone = value;
            return this;
        }

        public MemberOptions siteZone(String value) {
            siteZone = value;
            return this;
        }

        public MemberOptions pattern(String value) {
            pattern = value;
            return this;
        }

        public MemberOptions anchor(String value) {
            anchor = value;
            return this;
        }

        public MemberOptions phase(int value) {
            phase = value;
            return this;
        }

        public MemberOptions joinedOn(String value) {
            joinedOn = value;
            return this;
        }
    }

    public static CrewMember aMember() {
        return aMember(options -> {});
    }

    public static CrewMember aMember(Consumer<MemberOptions> change) {
        MemberOptions options = new MemberOptions();
        change.accept(options);
        return CrewMember.of(
                Ids.crewMemberId(options.id),
                options.displayName,
                options.homeZone,
                options.siteZone,
                RotationAssignment.of(
                        RotationPattern.parse(options.pattern), CivilDate.parse(options.anchor), options.phase),
                CivilDate.parse(options.joinedOn));
    }

    /** Ids that parse, handed out in order, so a test can name several records. */
    public static Supplier<String> idSequence(String prefix) {
        int[] next = {0};
        return () -> {
            next[0] += 1;
            return prefix + "_" + Texts.padStart(Integer.toString(next[0], 32).toUpperCase(Locale.ROOT), 16, '0');
        };
    }

    /** What a test may change about the default habit. */
    public static final class HabitOptions {
        private String id = "hab_0123456789ABCDEF";
        private String memberId = "mem_0123456789ABCDEF";
        private String name = "Twenty minutes on the treadmill";
        private String cadence = "1/onshift-day";
        private String target;
        private String startsOn = "2025-01-01";
        private String endsOn;
        private int graceDays;
        private List<String> tags = List.of();

        public HabitOptions id(String value) {
            id = value;
            return this;
        }

        public HabitOptions memberId(String value) {
            memberId = value;
            return this;
        }

        public HabitOptions name(String value) {
            name = value;
            return this;
        }

        public HabitOptions cadence(String value) {
            cadence = value;
            return this;
        }

        public HabitOptions target(String value) {
            target = value;
            return this;
        }

        public HabitOptions startsOn(String value) {
            startsOn = value;
            return this;
        }

        public HabitOptions endsOn(String value) {
            endsOn = value;
            return this;
        }

        public HabitOptions graceDays(int value) {
            graceDays = value;
            return this;
        }

        public HabitOptions tags(String... value) {
            tags = List.of(value);
            return this;
        }
    }

    /** A habit for the default member, running from the first day of 2025. */
    public static Habit aHabit() {
        return aHabit(options -> {});
    }

    public static Habit aHabit(Consumer<HabitOptions> change) {
        HabitOptions options = new HabitOptions();
        change.accept(options);
        return Habit.builder(
                        Ids.habitId(options.id),
                        Ids.crewMemberId(options.memberId),
                        options.name,
                        Cadence.parse(options.cadence),
                        CivilDate.parse(options.startsOn),
                        Zones.parseInstant("2024-12-30T09:00:00Z"))
                .target(Optional.ofNullable(options.target).map(HabitTarget::parse))
                .endsOn(Optional.ofNullable(options.endsOn).map(CivilDate::parse))
                .graceDays(options.graceDays)
                .tags(options.tags)
                .build();
    }
}
