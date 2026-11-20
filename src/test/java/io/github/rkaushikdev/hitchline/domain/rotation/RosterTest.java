package io.github.rkaushikdev.hitchline.domain.rotation;

import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.OFFSHIFT;
import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.ONSHIFT;
import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.TRAVEL;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RosterTest {

    private static final CrewMember MEMBER = Fixtures.aMember();
    private static final Instant ENTERED_AT = Zones.parseInstant("2025-01-18T10:00:00Z");
    private static final RosterOverride HELD_OVER = new RosterOverride(
            MEMBER.id(),
            r("2025-01-20..2025-01-22"),
            ONSHIFT,
            OverrideReason.HELD_OVER,
            "relief flight cancelled",
            ENTERED_AT);

    private static CivilDate d(String text) {
        return CivilDate.parse(text);
    }

    private static DateRange r(String text) {
        return DateRange.parse(text);
    }

    private static List<String> show(List<RoleBlock> blocks) {
        return blocks.stream().map(RoleBlock::describe).toList();
    }

    @Nested
    class ARosterWithNoOverrides {

        private final Roster plain = Roster.of(MEMBER);

        @Test
        void readsStraightThroughToThePattern() {
            assertThat(plain.effectiveRoleOn(d("2025-01-10")))
                    .isEqualTo(new EffectiveRole(ONSHIFT, RoleSource.PATTERN));
            assertThat(plain.roleFor(d("2025-01-25"))).isEqualTo(OFFSHIFT);
            assertThat(plain.isOnShift(d("2025-01-10"))).isTrue();
        }

        @Test
        void countsDutyDaysOverAHitch() {
            assertThat(plain.dutyDays(r("2025-01-06..2025-02-02"))).isEqualTo(14);
        }

        @Test
        void coversEveryDayOfAWindow() {
            assertThat(plain.isFullyCovered(r("2025-01-06..2025-02-02"))).isTrue();
        }
    }

    @Nested
    class ARosterWithAnOverride {

        private final Roster held = Roster.of(MEMBER, List.of(HELD_OVER));

        @Test
        void reportsTheOverrideAsTheSource() {
            assertThat(held.effectiveRoleOn(d("2025-01-21")))
                    .isEqualTo(new EffectiveRole(ONSHIFT, RoleSource.OVERRIDE));
        }

        @Test
        void leavesUntouchedDaysOnThePattern() {
            assertThat(held.effectiveRoleOn(d("2025-01-23")).source()).isEqualTo(RoleSource.PATTERN);
            assertThat(held.roleFor(d("2025-01-23"))).isEqualTo(OFFSHIFT);
        }

        @Test
        void addsTheHeldDaysToTheDutyCount() {
            assertThat(held.dutyDays(r("2025-01-06..2025-02-02"))).isEqualTo(17);
        }

        @Test
        void joinsTheHeldDaysOntoTheHitchAsOneBlock() {
            assertThat(show(held.effectiveBlocks(r("2025-01-06..2025-02-02"))))
                    .containsExactly("onshift 2025-01-06..2025-01-22", "offshift 2025-01-23..2025-02-02");
        }

        @Test
        void listsARoleForEveryDayOfTheWindow() {
            assertThat(held.rolesFor(r("2025-01-19..2025-01-24")))
                    .containsExactly(ONSHIFT, ONSHIFT, ONSHIFT, ONSHIFT, OFFSHIFT, OFFSHIFT);
        }
    }

    @Nested
    class AnOverrideThatCutsAHitchShort {

        private final Roster cut = Roster.of(
                MEMBER,
                List.of(RosterOverride.of(
                        MEMBER.id(), r("2025-01-15..2025-01-19"), OFFSHIFT, OverrideReason.MEDICAL, ENTERED_AT)));

        @Test
        void dropsTheDaysFromTheDutyCount() {
            assertThat(cut.dutyDays(r("2025-01-06..2025-02-02"))).isEqualTo(9);
        }

        @Test
        void splitsTheHitchIntoWhatWasWorkedAndWhatWasNot() {
            assertThat(show(cut.effectiveBlocks(r("2025-01-06..2025-01-25"))))
                    .containsExactly("onshift 2025-01-06..2025-01-14", "offshift 2025-01-15..2025-01-25");
        }

        @Test
        void countsTheOtherRolesToo() {
            assertThat(cut.countRoleFor(r("2025-01-06..2025-02-02"), OFFSHIFT)).isEqualTo(19);
            assertThat(cut.countRoleFor(r("2025-01-06..2025-02-02"), TRAVEL)).isZero();
        }
    }

    @Nested
    class AnOverrideForSomebodyElse {

        @Test
        void isIgnored() {
            CrewMember other =
                    Fixtures.aMember(o -> o.id("mem_FEDCBA9876543210").displayName("Bo Lindqvist"));
            Roster mixed = Roster.of(
                    MEMBER,
                    List.of(RosterOverride.of(
                            other.id(), r("2025-01-20..2025-01-22"), ONSHIFT, OverrideReason.SWAPPED, ENTERED_AT)));
            assertThat(mixed.roleFor(d("2025-01-21"))).isEqualTo(OFFSHIFT);
        }
    }
}
