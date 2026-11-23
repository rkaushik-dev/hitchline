package io.github.rkaushikdev.hitchline.domain.habit;

import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.OFFSHIFT;
import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.ONSHIFT;
import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.STANDBY;
import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.TRAVEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.support.Signatures;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CadenceTest {

    @Nested
    class BuildingACadence {

        @Test
        void countsTimesPerCalendarDay() {
            Cadence cadence = Cadence.perDay(2);
            assertThat(cadence.kind()).isEqualTo(CadenceKind.PER_DAY);
            assertThat(cadence).isEqualTo(new Cadence.PerDay(2));
        }

        @Test
        void countsTimesPerDayOfARole() {
            Cadence.PerRoleDay cadence = Cadence.perRoleDay(List.of(ONSHIFT), 1);
            assertThat(cadence.kind()).isEqualTo(CadenceKind.PER_ROLE_DAY);
            assertThat(cadence.roles()).containsExactly(ONSHIFT);
            assertThat(cadence.times()).isEqualTo(1);
        }

        @Test
        void dropsARepeatedRole() {
            assertThat(Cadence.perRoleDay(List.of(ONSHIFT, ONSHIFT, TRAVEL), 1).roles())
                    .containsExactly(ONSHIFT, TRAVEL);
        }

        @Test
        void refusesACadenceWithNoRoles() {
            assertThatThrownBy(() -> Cadence.perRoleDay(List.of(), 1)).hasMessageContaining("at least one day role");
        }

        @Test
        void refusesZeroOrFractionalTimes() {
            assertThatThrownBy(() -> Cadence.perDay(0)).hasMessageContaining("above zero");
            // A count of 1.5 cannot be written: times is an int.
            assertThat(Signatures.parameterTypes(Cadence.class, "perHitch")).containsExactly(List.of(int.class));
        }

        @Test
        void refusesAnUnreasonableNumberOfTimes() {
            assertThatThrownBy(() -> Cadence.perDay(49)).hasMessageContaining("at most 48");
        }

        @Test
        void boundsTheRollingWindow() {
            assertThatThrownBy(() -> Cadence.perWindow(3, 1)).hasMessageContaining("between 2 and 120");
            assertThatThrownBy(() -> Cadence.perWindow(3, 121)).hasMessageContaining("between 2 and 120");
            assertThat(Cadence.perWindow(3, 14).windowDays()).isEqualTo(14);
        }
    }

    @Nested
    class ParsingTheShortForms {

        @Test
        void readsACalendarCadence() {
            assertThat(Cadence.parse("2/day")).isEqualTo(Cadence.perDay(2));
        }

        @Test
        void readsAHitchCadence() {
            assertThat(Cadence.parse("3/hitch")).isEqualTo(Cadence.perHitch(3));
        }

        @Test
        void readsARoleDayCadence() {
            assertThat(Cadence.parse("1/onshift-day")).isEqualTo(Cadence.perRoleDay(List.of(ONSHIFT), 1));
        }

        @Test
        void acceptsTheShortRoleWords() {
            assertThat(Cadence.parse("1/on-day")).isEqualTo(Cadence.perRoleDay(List.of(ONSHIFT), 1));
            assertThat(Cadence.parse("1/off-day")).isEqualTo(Cadence.perRoleDay(List.of(OFFSHIFT), 1));
        }

        @Test
        void readsSeveralRolesJoinedTogether() {
            assertThat(Cadence.parse("1/onshift+standby-day"))
                    .isEqualTo(Cadence.perRoleDay(List.of(ONSHIFT, STANDBY), 1));
        }

        @Test
        void readsABlockCadence() {
            assertThat(Cadence.parse("1/onshift-block")).isEqualTo(Cadence.perBlock(ONSHIFT, 1));
        }

        @Test
        void readsARollingWindowCadence() {
            assertThat(Cadence.parse("5/14d")).isEqualTo(Cadence.perWindow(5, 14));
        }

        @Test
        void ignoresCaseAndSurroundingSpace() {
            assertThat(Cadence.parse("  3/HITCH ")).isEqualTo(Cadence.perHitch(3));
        }

        @Test
        void readsACalendarWeekCadence() {
            Cadence weekly = Cadence.parse("2/week");
            assertThat(weekly).isEqualTo(Cadence.perWeek(2));
            assertThat(weekly.kind()).isEqualTo(CadenceKind.PER_WEEK);
            assertThat(weekly.format()).isEqualTo("2/week");
            assertThat(weekly.isRoleBound()).isFalse();
            assertThat(weekly.rolesTouched()).isEmpty();
        }

        @Test
        void holdsAWeeklyCountToTheSameLimits() {
            assertThatThrownBy(() -> Cadence.parse("0/week")).hasMessageContaining("above zero");
            assertThatThrownBy(() -> Cadence.perWeek(49)).hasMessageContaining("at most 48");
        }

        @Test
        void roundTripsEveryShapeThroughItsShortForm() {
            for (String written : List.of(
                    "2/day",
                    "1/onshift-day",
                    "1/onshift+standby-day",
                    "3/hitch",
                    "2/week",
                    "1/offshift-block",
                    "5/14d")) {
                assertThat(Cadence.parse(written).format()).isEqualTo(written);
            }
        }

        @Test
        void refusesAUnitNoFormReads() {
            assertThatThrownBy(() -> Cadence.parse("1/fortnight"))
                    .hasMessageContaining("does not know the unit fortnight");
            assertThatThrownBy(() -> Cadence.parse("1/ashore-day")).hasMessageContaining("does not name a day role");
            assertThatThrownBy(() -> Cadence.parse("1/weekly")).hasMessageContaining("does not know the unit");
        }

        @Test
        void readsTheWireWordOfTheWeeklyShape() {
            assertThat(CadenceKind.fromWire("per_week")).contains(CadenceKind.PER_WEEK);
        }
    }

    @Nested
    class RejectingNonsense {

        @Test
        void refusesAStringWithNoSlash() {
            assertThatThrownBy(() -> Cadence.parse("every day")).hasMessageContaining("must look like 3/hitch");
        }

        @Test
        void refusesAnUnknownUnit() {
            assertThatThrownBy(() -> Cadence.parse("2/fortnight"))
                    .hasMessageContaining("does not know the unit fortnight");
        }

        @Test
        void refusesARoleWordItDoesNotRecognise() {
            assertThatThrownBy(() -> Cadence.parse("2/ashore-day"))
                    .hasMessageContaining("does not name a day role: ashore");
        }

        @Test
        void stillBoundsTheWindowWhenParsed() {
            assertThatThrownBy(() -> Cadence.parse("2/500d")).hasMessageContaining("between 2 and 120");
        }
    }

    @Nested
    class Formatting {

        @Test
        void roundTripsEveryShape() {
            for (String text :
                    List.of("2/day", "3/hitch", "1/onshift-day", "1/onshift+offshift-day", "2/travel-block", "5/14d")) {
                assertThat(Cadence.parse(text).format()).isEqualTo(text);
            }
        }

        @Test
        void normalisesTheShortRoleWordsOnTheWayOut() {
            assertThat(Cadence.parse("1/on-day").format()).isEqualTo("1/onshift-day");
        }
    }

    @Nested
    class WhatACadenceDependsOn {

        @Test
        void knowsWhichShapesAreTiedToARole() {
            assertThat(Cadence.perRoleDay(List.of(ONSHIFT), 1).isRoleBound()).isTrue();
            assertThat(Cadence.perBlock(OFFSHIFT, 1).isRoleBound()).isTrue();
            assertThat(Cadence.perDay(1).isRoleBound()).isFalse();
            assertThat(Cadence.perHitch(1).isRoleBound()).isFalse();
            assertThat(Cadence.perWindow(1, 7).isRoleBound()).isFalse();
        }

        @Test
        void listsTheRolesItReads() {
            assertThat(Cadence.perRoleDay(List.of(ONSHIFT, TRAVEL), 1).rolesTouched())
                    .containsExactly(ONSHIFT, TRAVEL);
            assertThat(Cadence.perBlock(OFFSHIFT, 1).rolesTouched()).containsExactly(OFFSHIFT);
            assertThat(Cadence.perDay(1).rolesTouched()).isEmpty();
        }
    }
}
