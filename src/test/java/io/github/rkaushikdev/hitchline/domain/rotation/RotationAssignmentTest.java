package io.github.rkaushikdev.hitchline.domain.rotation;

import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.OFFSHIFT;
import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.ONSHIFT;
import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.TRAVEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.support.Signatures;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RotationAssignmentTest {

    private static final RotationPattern FORTNIGHTS = RotationPattern.parse("14/14");
    private static final RotationPattern WITH_TRAVEL = RotationPattern.parse("1travel 13on 1travel 13off");
    private static final RotationAssignment BASE = RotationAssignment.of(FORTNIGHTS, d("2025-01-06"));

    private static CivilDate d(String text) {
        return CivilDate.parse(text);
    }

    private static DateRange r(String text) {
        return DateRange.parse(text);
    }

    @Nested
    class CrewChangeDays {

        @Test
        void startsAFortnightEachWayAtTheTopOfTheCycle() {
            RotationAssignment assignment =
                    RotationAssignment.fromCrewChange(RotationPattern.parse("14/14"), CivilDate.parse("2025-03-03"));
            assertThat(assignment.phase()).isZero();
            assertThat(assignment.roleOn(CivilDate.parse("2025-03-03"))).isEqualTo(DayRole.ONSHIFT);
        }

        @Test
        void startsTheTripOnTheTravelDayWhenThePatternHasOne() {
            assertThat(RotationAssignment.tripStartIndex(RotationPattern.parse("1travel 13on 1travel 13off")))
                    .isZero();
            assertThat(RotationAssignment.tripStartIndex(RotationPattern.parse("13off 1travel 13on 1travel")))
                    .isEqualTo(13);
        }

        @Test
        void stepsOverHomeDaysAtTheStartOfTheCycle() {
            RotationPattern pattern = RotationPattern.parse("2standby 5on 2off");
            RotationAssignment assignment = RotationAssignment.fromCrewChange(pattern, CivilDate.parse("2025-03-03"));
            assertThat(assignment.phase()).isEqualTo(2);
            assertThat(assignment.roleOn(CivilDate.parse("2025-03-02"))).isEqualTo(DayRole.STANDBY);
            assertThat(assignment.roleOn(CivilDate.parse("2025-03-07"))).isEqualTo(DayRole.ONSHIFT);
            assertThat(assignment.roleOn(CivilDate.parse("2025-03-08"))).isEqualTo(DayRole.OFFSHIFT);
        }

        @Test
        void startsACycleWithNoHomeDaysAtZero() {
            assertThat(RotationAssignment.tripStartIndex(RotationPattern.parse("3on 2travel")))
                    .isZero();
        }
    }

    @Nested
    class Construction {

        @Test
        void defaultsThePhaseToZero() {
            assertThat(BASE.phase()).isZero();
        }

        @Test
        void refusesAPhaseOutsideTheCycle() {
            assertThatThrownBy(() -> RotationAssignment.of(FORTNIGHTS, d("2025-01-06"), 28))
                    .hasMessageContaining("between 0 and 27");
        }

        @Test
        void refusesAFractionalPhase() {
            // A phase of 1.5 cannot be written: the phase is a whole number of days, an int.
            assertThat(Signatures.parameterTypes(RotationAssignment.class, "of"))
                    .contains(List.of(RotationPattern.class, CivilDate.class, int.class));
        }
    }

    @Nested
    class RolesAcrossTheCycle {

        @Test
        void startsOnShiftAtTheAnchor() {
            assertThat(BASE.roleOn(d("2025-01-06"))).isEqualTo(ONSHIFT);
            assertThat(BASE.cycleIndexOn(d("2025-01-06"))).isZero();
        }

        @Test
        void turnsOverAfterFourteenDays() {
            assertThat(BASE.roleOn(d("2025-01-19"))).isEqualTo(ONSHIFT);
            assertThat(BASE.roleOn(d("2025-01-20"))).isEqualTo(OFFSHIFT);
            assertThat(BASE.roleOn(d("2025-02-02"))).isEqualTo(OFFSHIFT);
            assertThat(BASE.roleOn(d("2025-02-03"))).isEqualTo(ONSHIFT);
        }

        @Test
        void runsBackwardsFromTheAnchorToo() {
            assertThat(BASE.roleOn(d("2025-01-05"))).isEqualTo(OFFSHIFT);
            assertThat(BASE.cycleIndexOn(d("2025-01-05"))).isEqualTo(27);
        }

        @Test
        void placesTheBackToBackCrewHalfACycleAway() {
            RotationAssignment backToBack = RotationAssignment.of(FORTNIGHTS, d("2025-01-06"), 14);
            assertThat(backToBack.roleOn(d("2025-01-06"))).isEqualTo(OFFSHIFT);
            assertThat(backToBack.roleOn(d("2025-01-20"))).isEqualTo(ONSHIFT);
        }

        @Test
        void readsTravelDaysWhereThePatternHasThem() {
            RotationAssignment flying = RotationAssignment.of(WITH_TRAVEL, d("2025-01-06"));
            assertThat(flying.roleOn(d("2025-01-06"))).isEqualTo(TRAVEL);
            assertThat(flying.roleOn(d("2025-01-07"))).isEqualTo(ONSHIFT);
            assertThat(flying.roleOn(d("2025-01-20"))).isEqualTo(TRAVEL);
            assertThat(flying.roleOn(d("2025-01-21"))).isEqualTo(OFFSHIFT);
        }
    }

    @Nested
    class HitchNumbering {

        @Test
        void countsTheAnchorCycleAsZero() {
            assertThat(BASE.hitchNumberOn(d("2025-01-06"))).isZero();
            assertThat(BASE.hitchNumberOn(d("2025-02-02"))).isZero();
            assertThat(BASE.hitchNumberOn(d("2025-02-03"))).isEqualTo(1);
        }

        @Test
        void goesNegativeBeforeTheAnchor() {
            assertThat(BASE.hitchNumberOn(d("2025-01-05"))).isEqualTo(-1);
        }

        @Test
        void givesTheDatesOfANumberedHitch() {
            assertThat(BASE.hitchRange(0).toString()).isEqualTo("2025-01-06..2025-02-02");
            assertThat(BASE.hitchRange(1).toString()).isEqualTo("2025-02-03..2025-03-02");
            assertThat(BASE.hitchRange(-1).toString()).isEqualTo("2024-12-09..2025-01-05");
        }

        @Test
        void findsTheHitchADateBelongsTo() {
            assertThat(BASE.hitchRangeOn(d("2025-01-30")).toString()).isEqualTo("2025-01-06..2025-02-02");
        }

        @Test
        void shiftsTheHitchWindowWhenThePhaseMoves() {
            RotationAssignment shifted = RotationAssignment.of(FORTNIGHTS, d("2025-01-06"), 7);
            assertThat(shifted.hitchRange(0).toString()).isEqualTo("2024-12-30..2025-01-26");
        }
    }

    @Nested
    class Blocks {

        @Test
        void findsTheRunOfDaysAroundADate() {
            RoleBlock block = BASE.blockContaining(d("2025-01-10"));
            assertThat(block.role()).isEqualTo(ONSHIFT);
            assertThat(block.range().toString()).isEqualTo("2025-01-06..2025-01-19");
        }

        @Test
        void findsASingleDayTravelBlock() {
            RotationAssignment flying = RotationAssignment.of(WITH_TRAVEL, d("2025-01-06"));
            assertThat(flying.blockContaining(d("2025-01-06")).range().toString())
                    .isEqualTo("2025-01-06..2025-01-06");
        }

        @Test
        void reportsTheNextChangeOfRole() {
            assertThat(BASE.nextRoleChange(d("2025-01-10")).toString()).isEqualTo("2025-01-20");
        }

        @Test
        void splitsARangeIntoBlocksAndClipsTheEnds() {
            assertThat(BASE.blocksOver(r("2025-01-15..2025-02-05")).stream().map(RoleBlock::describe))
                    .containsExactly(
                            "onshift 2025-01-15..2025-01-19",
                            "offshift 2025-01-20..2025-02-02",
                            "onshift 2025-02-03..2025-02-05");
        }
    }

    @Nested
    class CountingOverARange {

        @Test
        void listsARoleForEveryDay() {
            assertThat(BASE.rolesOver(r("2025-01-18..2025-01-21")))
                    .containsExactly(ONSHIFT, ONSHIFT, OFFSHIFT, OFFSHIFT);
        }

        @Test
        void countsDaysOfOneRole() {
            assertThat(BASE.countRoleOver(r("2025-01-06..2025-02-02"), ONSHIFT)).isEqualTo(14);
            assertThat(BASE.countRoleOver(r("2025-01-06..2025-02-02"), TRAVEL)).isZero();
        }
    }

    @Nested
    class EffectiveWindow {

        @Test
        void isAlwaysActiveWhenNoWindowIsSet() {
            assertThat(BASE.isActiveOn(d("2030-01-01"))).isTrue();
        }

        @Test
        void honoursTheWindowWhenOneIsSet() {
            RotationAssignment bounded =
                    RotationAssignment.of(FORTNIGHTS, d("2025-01-06"), 0, r("2025-01-06..2025-06-30"));
            assertThat(bounded.isActiveOn(d("2025-03-01"))).isTrue();
            assertThat(bounded.isActiveOn(d("2025-07-01"))).isFalse();
        }

        @Test
        void describesItselfForALogLine() {
            assertThat(BASE.describe()).contains("anchored 2025-01-06");
        }
    }
}
