package io.github.rkaushikdev.hitchline.domain.rotation;

import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.OFFSHIFT;
import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.ONSHIFT;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RoleCalendarTest {

    private static final CrewMember MEMBER = Fixtures.aMember();
    private static final Roster PLAIN = Roster.of(MEMBER);

    private static CivilDate d(String text) {
        return CivilDate.parse(text);
    }

    private static DateRange r(String text) {
        return DateRange.parse(text);
    }

    @Nested
    class BuildingOne {

        @Test
        void hasARoleForEveryDayInTheWindow() {
            RoleCalendar calendar = RoleCalendar.build(PLAIN, r("2025-01-06..2025-01-12"));
            assertThat(calendar.roles()).hasSize(7);
            assertThat(calendar.roles()).allMatch(role -> role == ONSHIFT);
        }

        @Test
        void readsASingleDayWindow() {
            assertThat(RoleCalendar.build(PLAIN, r("2025-01-06..2025-01-06")).roles())
                    .containsExactly(ONSHIFT);
        }

        @Test
        void followsTheOverridesTheRosterCarries() {
            Roster held = Roster.of(
                    MEMBER,
                    List.of(RosterOverride.of(
                            MEMBER.id(),
                            r("2025-01-20..2025-01-22"),
                            ONSHIFT,
                            OverrideReason.HELD_OVER,
                            Zones.parseInstant("2025-01-18T10:00:00Z"))));
            assertThat(RoleCalendar.build(held, r("2025-01-19..2025-01-23")).roles())
                    .containsExactly(ONSHIFT, ONSHIFT, ONSHIFT, ONSHIFT, OFFSHIFT);
        }
    }

    @Nested
    class ReadingADay {

        private final RoleCalendar calendar = RoleCalendar.build(PLAIN, r("2025-01-06..2025-01-31"));

        @Test
        void answersFromTheArrayInsideTheWindow() {
            assertThat(calendar.roleOnDay(d("2025-01-10"))).hasValue(ONSHIFT);
            assertThat(calendar.roleOnDay(d("2025-01-25"))).hasValue(OFFSHIFT);
        }

        @Test
        void answersNothingOutsideIt() {
            assertThat(calendar.roleOnDay(d("2025-02-10"))).isEmpty();
        }

        @Test
        void fallsThroughToTheRosterOutsideIt() {
            assertThat(calendar.roleThrough(PLAIN, d("2025-02-20"))).isEqualTo(OFFSHIFT);
            assertThat(calendar.roleThrough(PLAIN, d("2025-02-05"))).isEqualTo(ONSHIFT);
        }

        @Test
        void agreesWithTheRosterEverywhereInsideIt() {
            assertThat(calendar.roles()).doesNotContainNull();
            assertThat(calendar.countIn(ONSHIFT)).isEqualTo(14);
            assertThat(calendar.countIn(OFFSHIFT)).isEqualTo(12);
        }
    }

    @Nested
    class Blocks {

        @Test
        void collectsTheRunsOfSameRoleDays() {
            RoleCalendar calendar = RoleCalendar.build(PLAIN, r("2025-01-06..2025-02-05"));
            assertThat(calendar.blocks().stream().map(RoleBlock::describe))
                    .containsExactly(
                            "onshift 2025-01-06..2025-01-19",
                            "offshift 2025-01-20..2025-02-02",
                            "onshift 2025-02-03..2025-02-05");
        }
    }

    @Nested
    class DescribingOne {

        @Test
        void readsBackAsAWindowAndACount() {
            assertThat(RoleCalendar.build(PLAIN, r("2025-01-06..2025-01-12")).describe())
                    .isEqualTo("2025-01-06 to 2025-01-12, 7 days");
        }
    }
}
