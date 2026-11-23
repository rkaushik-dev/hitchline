package io.github.rkaushikdev.hitchline.domain.rotation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HitchVarianceTest {

    private static final CrewMember ADA = Fixtures.aMember();

    private static RosterOverride entry(String range, DayRole role, OverrideReason reason) {
        return RosterOverride.of(
                ADA.id(), DateRange.parse(range), role, reason, Zones.parseInstant("2025-01-01T00:00:00Z"));
    }

    @Nested
    class AHitchWorkedAsRostered {

        @Test
        void countsTheFortnightOnBothSides() {
            List<HitchVariance> hitches = HitchVariance.over(Roster.of(ADA), DateRange.parse("2025-01-10..2025-01-10"));
            assertThat(hitches).hasSize(1);
            HitchVariance hitch = hitches.get(0);
            assertThat(hitch.hitch()).isEqualTo(DateRange.parse("2025-01-06..2025-02-02"));
            assertThat(hitch.scheduled()).isEqualTo(14);
            assertThat(hitch.worked()).isEqualTo(14);
            assertThat(hitch.asRostered()).isTrue();
            assertThat(hitch.describeReasons()).isEqualTo("-");
        }

        @Test
        void givesOneLinePerHitchTheWindowTouches() {
            assertThat(HitchVariance.over(Roster.of(ADA), DateRange.parse("2025-01-20..2025-03-05")))
                    .extracting(HitchVariance::hitch)
                    .containsExactly(
                            DateRange.parse("2025-01-06..2025-02-02"),
                            DateRange.parse("2025-02-03..2025-03-02"),
                            DateRange.parse("2025-03-03..2025-03-30"));
        }
    }

    @Nested
    class AHitchTheClerkChanged {

        @Test
        void countsDaysHeldOverAsDaysOverTheRota() {
            Roster held =
                    Roster.of(ADA, List.of(entry("2025-01-20..2025-01-22", DayRole.ONSHIFT, OverrideReason.HELD_OVER)));
            HitchVariance hitch = HitchVariance.over(held, DateRange.parse("2025-01-06..2025-01-06"))
                    .get(0);
            assertThat(hitch.worked()).isEqualTo(17);
            assertThat(hitch.over()).isEqualTo(3);
            assertThat(hitch.under()).isZero();
            assertThat(hitch.reasons()).isEqualTo(Map.of(OverrideReason.HELD_OVER, 3));
            assertThat(hitch.asRostered()).isFalse();
        }

        @Test
        void countsDaysSentHomeAsDaysUnderAndNamesEachReason() {
            Roster changed = Roster.of(
                    ADA,
                    List.of(
                            entry("2025-01-16..2025-01-19", DayRole.OFFSHIFT, OverrideReason.MEDICAL),
                            entry("2025-01-20..2025-01-21", DayRole.ONSHIFT, OverrideReason.WEATHER)));
            HitchVariance hitch = HitchVariance.over(changed, DateRange.parse("2025-01-06..2025-01-06"))
                    .get(0);
            assertThat(hitch.scheduled()).isEqualTo(14);
            assertThat(hitch.worked()).isEqualTo(12);
            assertThat(hitch.under()).isEqualTo(4);
            assertThat(hitch.over()).isEqualTo(2);
            assertThat(hitch.describeReasons()).isEqualTo("medical 4, weather 2");
        }

        @Test
        void leavesOutEntriesThatChangeNothing() {
            Roster same =
                    Roster.of(ADA, List.of(entry("2025-01-08..2025-01-09", DayRole.ONSHIFT, OverrideReason.SWAPPED)));
            HitchVariance hitch = HitchVariance.over(same, DateRange.parse("2025-01-06..2025-01-06"))
                    .get(0);
            assertThat(hitch.asRostered()).isTrue();
            assertThat(hitch.reasons()).isEmpty();
        }
    }
}
