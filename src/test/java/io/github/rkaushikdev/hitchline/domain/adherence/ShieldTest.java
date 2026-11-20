package io.github.rkaushikdev.hitchline.domain.adherence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ShieldTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String BO = Ids.crewMemberId("mem_FEDCBA9876543210");
    private static final Instant GRANTED_AT = Zones.parseInstant("2025-01-04T10:00:00Z");

    private static CivilDate d(String text) {
        return CivilDate.parse(text);
    }

    private static DateRange r(String text) {
        return DateRange.parse(text);
    }

    /** What a test may change about the default shield. */
    private record Draft(
            String memberId, DateRange range, ShieldReason reason, String note, Optional<String> grantedBy) {
        Draft member(String value) {
            return new Draft(value, range, reason, note, grantedBy);
        }

        Draft range(String value) {
            return new Draft(memberId, r(value), reason, note, grantedBy);
        }

        Draft reason(ShieldReason value) {
            return new Draft(memberId, range, value, note, grantedBy);
        }

        Draft note(String value) {
            return new Draft(memberId, range, reason, value, grantedBy);
        }

        Draft grantedBy(String value) {
            return new Draft(memberId, range, reason, note, Optional.of(value));
        }
    }

    private static Shield a() {
        return a(UnaryOperator.identity());
    }

    private static Shield a(UnaryOperator<Draft> change) {
        Draft draft = change.apply(
                new Draft(ADA, r("2025-01-20..2025-01-26"), ShieldReason.ANNUAL_LEAVE, null, Optional.empty()));
        return new Shield(draft.memberId(), draft.range(), draft.reason(), draft.note(), draft.grantedBy(), GRANTED_AT);
    }

    @Nested
    class GrantingAShield {

        @Test
        void letsAMemberDeclareLeaveOrSicknessThemselves() {
            assertThat(a().grantedBy()).isEmpty();
            assertThat(a(o -> o.reason(ShieldReason.SICK)).grantedBy()).isEmpty();
        }

        @Test
        void needsASupervisorForTheOtherReasons() {
            assertThatThrownBy(() -> a(o -> o.reason(ShieldReason.TRAINING)))
                    .hasMessageContaining("is needed to shield days for training");
            assertThat(a(o -> o.reason(ShieldReason.TRAINING).grantedBy(BO)).grantedBy())
                    .hasValue(BO);
        }

        @Test
        void trimsTheNoteAndBoundsIt() {
            assertThat(a(o -> o.note("  two weeks off  ")).note()).isEqualTo("two weeks off");
            assertThatThrownBy(() -> a(o -> o.note("x".repeat(241)))).hasMessageContaining("at most 240 characters");
        }

        @Test
        void refusesAStretchLongerThanHalfAYear() {
            assertThatThrownBy(() -> a(o -> o.range("2025-01-01..2025-12-31")))
                    .hasMessageContaining("more than the 180 allowed");
        }
    }

    @Nested
    class FindingShields {

        private final List<Shield> shields =
                List.of(a(), a(o -> o.range("2025-03-03..2025-03-09").reason(ShieldReason.SICK)), a(o -> o.member(BO)
                        .range("2025-01-20..2025-01-26")));

        @Test
        void filtersToOneMember() {
            assertThat(Shield.shieldsFor(shields, ADA)).hasSize(2);
            assertThat(Shield.shieldsFor(shields, BO)).hasSize(1);
        }

        @Test
        void findsTheOneCoveringADay() {
            assertThat(Shield.shieldOn(shields, ADA, d("2025-01-22")).map(Shield::reason))
                    .hasValue(ShieldReason.ANNUAL_LEAVE);
            assertThat(Shield.shieldOn(shields, ADA, d("2025-01-27"))).isEmpty();
        }

        @Test
        void answersWhetherADayIsShielded() {
            assertThat(Shield.isShielded(shields, ADA, d("2025-01-20"))).isTrue();
            assertThat(Shield.isShielded(shields, ADA, d("2025-01-19"))).isFalse();
            assertThat(Shield.isShielded(shields, BO, d("2025-03-05"))).isFalse();
        }

        @Test
        void listsWhatTouchesAWindow() {
            assertThat(Shield.shieldsOver(shields, ADA, r("2025-01-01..2025-01-31")))
                    .hasSize(1);
            assertThat(Shield.shieldsOver(shields, ADA, r("2025-01-01..2025-12-31")))
                    .hasSize(2);
            assertThat(Shield.shieldsOver(shields, ADA, r("2025-06-01..2025-06-30")))
                    .isEmpty();
        }
    }

    @Nested
    class CountingShieldedDays {

        @Test
        void countsTheDaysInsideAWindow() {
            assertThat(Shield.shieldedDayCount(List.of(a()), ADA, r("2025-01-01..2025-01-31")))
                    .isEqualTo(7);
        }

        @Test
        void clipsToTheWindow() {
            assertThat(Shield.shieldedDayCount(List.of(a()), ADA, r("2025-01-22..2025-01-31")))
                    .isEqualTo(5);
        }

        @Test
        void countsAnOverlappingPairOnce() {
            List<Shield> overlapping =
                    List.of(a(), a(o -> o.range("2025-01-24..2025-01-30").reason(ShieldReason.SICK)));
            assertThat(Shield.shieldedDayCount(overlapping, ADA, r("2025-01-01..2025-01-31")))
                    .isEqualTo(11);
        }

        @Test
        void countsNothingForAMemberWithNoShields() {
            assertThat(Shield.shieldedDayCount(List.of(a()), BO, r("2025-01-01..2025-01-31")))
                    .isZero();
        }
    }

    @Nested
    class CoveredRanges {

        @Test
        void mergesTheEntriesIntoRuns() {
            List<Shield> shields =
                    List.of(a(), a(o -> o.range("2025-01-27..2025-01-31").reason(ShieldReason.SICK)));
            assertThat(Shield.coveredRanges(shields, ADA).stream().map(DateRange::toString))
                    .containsExactly("2025-01-20..2025-01-31");
        }

        @Test
        void keepsSeparateRunsApart() {
            List<Shield> shields =
                    List.of(a(), a(o -> o.range("2025-03-03..2025-03-09").reason(ShieldReason.SICK)));
            assertThat(Shield.coveredRanges(shields, ADA)).hasSize(2);
        }
    }

    @Nested
    class DescribingAShield {

        @Test
        void saysWhoGrantedIt() {
            assertThat(a().describe()).isEqualTo("annual_leave self declared");
            assertThat(a(o -> o.reason(ShieldReason.TRAINING).grantedBy(BO)).describe())
                    .isEqualTo("training granted by " + BO);
        }
    }
}
