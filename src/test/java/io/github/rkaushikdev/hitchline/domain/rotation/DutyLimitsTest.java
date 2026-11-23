package io.github.rkaushikdev.hitchline.domain.rotation;

import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.OFFSHIFT;
import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.ONSHIFT;
import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.STANDBY;
import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.TRAVEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DutyLimitsTest {

    private static final CrewMember ADA = Fixtures.aMember();
    private static final DateRange QUARTER = DateRange.parse("2025-01-01..2025-03-31");

    private static RosterOverride entry(String range, DayRole role, OverrideReason reason) {
        return RosterOverride.of(
                ADA.id(), DateRange.parse(range), role, reason, Zones.parseInstant("2025-01-01T00:00:00Z"));
    }

    @Nested
    class APlainRota {

        @Test
        void keepsAFortnightEachWayInsideTheDefaults() {
            assertThat(DutyLimits.DEFAULT.check(Roster.of(ADA), QUARTER)).isEmpty();
        }

        @Test
        void flagsEveryTripOfARotaLongerThanTheLimit() {
            List<DutyBreach> breaches = DutyLimits.of(10, 7).check(Roster.of(ADA), QUARTER);
            assertThat(breaches).extracting(DutyBreach::rule).containsOnly(DutyRule.LONG_TRIP);
            assertThat(breaches.get(0).range()).isEqualTo(DateRange.parse("2025-01-06..2025-01-19"));
            assertThat(breaches.get(0).days()).isEqualTo(14);
            assertThat(breaches.get(0).limit()).isEqualTo(10);
        }

        @Test
        void countsTravelDaysAsDaysAway() {
            CrewMember flyer = Fixtures.aMember(options -> options.pattern("1travel 13on 1travel 13off"));
            List<DutyBreach> breaches = DutyLimits.of(14, 7).check(Roster.of(flyer), QUARTER);
            assertThat(breaches).isNotEmpty();
            assertThat(breaches.get(0).days()).isEqualTo(15);
        }

        @Test
        void treatsStandbyAsTimeAtHome() {
            CrewMember onCall = Fixtures.aMember(options -> options.pattern("14on 7standby 7off"));
            assertThat(DutyLimits.of(21, 14).check(Roster.of(onCall), QUARTER)).isEmpty();
            assertThat(STANDBY).matches(role -> !DutyLimits.isAway(role));
            assertThat(TRAVEL).matches(DutyLimits::isAway);
        }
    }

    @Nested
    class AHeldOverRota {

        @Test
        void flagsATripHeldPastTwentyOneDaysAndTheBreakItEatsInto() {
            Roster held = Roster.of(ADA, List.of(entry("2025-01-20..2025-01-27", ONSHIFT, OverrideReason.HELD_OVER)));
            List<DutyBreach> breaches = DutyLimits.DEFAULT.check(held, QUARTER);
            assertThat(breaches)
                    .containsExactly(
                            new DutyBreach(DutyRule.LONG_TRIP, DateRange.parse("2025-01-06..2025-01-27"), 22, 21),
                            new DutyBreach(DutyRule.SHORT_BREAK, DateRange.parse("2025-01-28..2025-02-02"), 6, 7));
            assertThat(breaches.get(0).describe())
                    .isEqualTo("long_trip 2025-01-06..2025-01-27: 22 days away, limit 21");
        }

        @Test
        void flagsACallOutThatCutsTheBreakShort() {
            Roster called =
                    Roster.of(ADA, List.of(entry("2025-01-24..2025-02-02", ONSHIFT, OverrideReason.CALLED_OUT)));
            List<DutyBreach> breaches = DutyLimits.DEFAULT.check(called, QUARTER);
            assertThat(breaches)
                    .containsExactly(
                            new DutyBreach(DutyRule.SHORT_BREAK, DateRange.parse("2025-01-20..2025-01-23"), 4, 7),
                            new DutyBreach(DutyRule.LONG_TRIP, DateRange.parse("2025-01-24..2025-02-16"), 24, 21));
            assertThat(breaches.get(0).describe())
                    .isEqualTo("short_break 2025-01-20..2025-01-23: 4 days at home, at least 7");
        }

        @Test
        void measuresATripWholeWhenOnlyItsTailIsInTheWindow() {
            Roster held = Roster.of(ADA, List.of(entry("2025-01-20..2025-01-27", ONSHIFT, OverrideReason.HELD_OVER)));
            List<DutyBreach> breaches = DutyLimits.DEFAULT.check(held, DateRange.parse("2025-01-27..2025-01-27"));
            assertThat(breaches).extracting(DutyBreach::days).containsExactly(22);
        }

        @Test
        void leavesOutBreachesThatDoNotTouchTheWindow() {
            Roster held = Roster.of(ADA, List.of(entry("2025-01-20..2025-01-27", ONSHIFT, OverrideReason.HELD_OVER)));
            assertThat(DutyLimits.DEFAULT.check(held, DateRange.parse("2025-03-01..2025-03-31")))
                    .isEmpty();
        }

        @Test
        void readsASentHomeEarlyHitchAsALongerBreak() {
            Roster home = Roster.of(ADA, List.of(entry("2025-01-15..2025-01-19", OFFSHIFT, OverrideReason.MEDICAL)));
            DateRange window = DateRange.parse("2025-01-15..2025-02-02");
            assertThat(DutyLimits.of(21, 18).check(home, window)).isEmpty();
            assertThat(DutyLimits.of(21, 20).check(home, window))
                    .extracting(DutyBreach::range)
                    .containsExactly(DateRange.parse("2025-01-15..2025-02-02"));
        }
    }

    @Nested
    class Limits {

        @Test
        void refusesATripLimitOutsideOneToSixtyDays() {
            assertThatThrownBy(() -> DutyLimits.of(0, 7)).hasMessageContaining("maxTripDays");
            assertThatThrownBy(() -> DutyLimits.of(61, 7)).hasMessageContaining("maxTripDays");
        }

        @Test
        void refusesABreakLimitOutsideZeroToSixtyDays() {
            assertThatThrownBy(() -> DutyLimits.of(21, -1)).hasMessageContaining("minBreakDays");
            assertThatThrownBy(() -> DutyLimits.of(21, 61)).hasMessageContaining("minBreakDays");
            assertThat(DutyLimits.of(21, 0).minBreakDays()).isZero();
        }

        @Test
        void readsTheRulesBackByTheirWireWords() {
            assertThat(DutyRule.fromWire("long_trip")).contains(DutyRule.LONG_TRIP);
            assertThat(DutyRule.fromWire("short_break")).contains(DutyRule.SHORT_BREAK);
            assertThat(DutyRule.SHORT_BREAK.toString()).isEqualTo("short_break");
        }
    }
}
