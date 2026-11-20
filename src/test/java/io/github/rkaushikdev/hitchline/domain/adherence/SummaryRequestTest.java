package io.github.rkaushikdev.hitchline.domain.adherence;

import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.MET;
import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.MISSED;
import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.PARTIAL;
import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.support.Builders;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SummaryRequestTest {

    private static final CrewMember MEMBER = Fixtures.aMember();
    private static final Roster PLAIN = Roster.of(MEMBER);
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");
    private static final String STRETCHES = Ids.habitId("hab_FEDCBA9876543210");

    private static final Shield LEAVE = Shield.of(
            Ids.crewMemberId("mem_0123456789ABCDEF"),
            DateRange.parse("2025-01-13..2025-01-15"),
            ShieldReason.ANNUAL_LEAVE,
            Zones.parseInstant("2025-01-04T10:00:00Z"));

    private static final List<ObligationResult> RESULTS = List.of(
            result("2025-01-10", MET),
            result("2025-01-11", MISSED),
            result("2025-01-12", PARTIAL),
            result("2025-01-13", MET),
            result("2025-02-05", MET),
            result("2025-02-06", PENDING));

    private static ObligationResult result(String day, AdherenceStatus status) {
        return Builders.aResult(day, status, TREADMILL);
    }

    private static SummaryRequest request(List<ObligationResult> results, List<Shield> shields, String range) {
        return new SummaryRequest(PLAIN, results, shields, DateRange.parse(range));
    }

    @Nested
    class SummarisingAWindow {

        private final PeriodSummary summary =
                request(RESULTS, List.of(LEAVE), "2025-01-01..2025-01-31").summarise();

        @Test
        void countsWhatWasOwedAndHowItWent() {
            assertThat(summary.owed()).isEqualTo(4);
            assertThat(summary.met()).isEqualTo(2);
            assertThat(summary.missed()).isEqualTo(1);
            assertThat(summary.partial()).isEqualTo(1);
            assertThat(summary.pending()).isZero();
        }

        @Test
        void ratesTheSettledObligations() {
            assertThat(summary.rate()).isEqualTo(0.5);
        }

        @Test
        void countsTheShieldedDaysInTheWindow() {
            assertThat(summary.shieldedDays()).isEqualTo(3);
        }

        @Test
        void leavesOutObligationsFromOutsideTheWindow() {
            assertThat(summary.owed()).isLessThan(RESULTS.size());
        }

        @Test
        void readsBackAsALine() {
            assertThat(summary.describe()).isEqualTo("2025-01-01..2025-01-31 2 of 4 at 50%");
        }
    }

    @Nested
    class ACleanWindow {

        @Test
        void hasNothingMissedOrPartial() {
            PeriodSummary summary = request(
                            List.of(result("2025-01-10", MET), result("2025-01-11", PENDING)),
                            List.of(),
                            "2025-01-01..2025-01-31")
                    .summarise();
            assertThat(summary.isClean()).isTrue();
            assertThat(summary.pending()).isEqualTo(1);
        }

        @Test
        void isNotCleanOnceSomethingIsMissed() {
            PeriodSummary summary = request(List.of(result("2025-01-11", MISSED)), List.of(), "2025-01-01..2025-01-31")
                    .summarise();
            assertThat(summary.isClean()).isFalse();
        }
    }

    @Nested
    class SummarisingByHabit {

        @Test
        void splitsTheWindowByHabitAndSortsById() {
            List<HabitSummary> byHabit = request(
                            List.of(
                                    result("2025-01-10", MET),
                                    result("2025-01-11", MISSED),
                                    Builders.aResult("2025-01-10", MET, STRETCHES)),
                            List.of(),
                            "2025-01-01..2025-01-31")
                    .byHabit();
            assertThat(byHabit.stream().map(HabitSummary::habitId))
                    .containsExactlyElementsOf(
                            Stream.of(TREADMILL, STRETCHES).sorted().toList());
            assertThat(byHabit.stream()
                            .filter(entry -> entry.habitId().equals(STRETCHES))
                            .findFirst()
                            .orElseThrow()
                            .rate())
                    .isEqualTo(1.0);
            assertThat(byHabit.stream()
                            .filter(entry -> entry.habitId().equals(TREADMILL))
                            .findFirst()
                            .orElseThrow()
                            .rate())
                    .isEqualTo(0.5);
        }

        @Test
        void returnsNothingForAWindowWithNoObligations() {
            assertThat(request(RESULTS, List.of(), "2025-06-01..2025-06-30").byHabit())
                    .isEmpty();
        }
    }

    @Nested
    class SummarisingByHitch {

        private final List<PeriodSummary> byHitch =
                request(RESULTS, List.of(), "2025-01-10..2025-02-06").byHitch();

        @Test
        void givesOneSummaryPerTurnOfTheRotation() {
            assertThat(byHitch.stream().map(entry -> entry.range().toString()))
                    .containsExactly("2025-01-06..2025-02-02", "2025-02-03..2025-03-02");
        }

        @Test
        void putsEachObligationInTheHitchItFellDueIn() {
            assertThat(byHitch.get(0).owed()).isEqualTo(4);
            assertThat(byHitch.get(1).owed()).isEqualTo(2);
        }

        @Test
        void coversTheWholeHitchEvenWhenTheWindowIsNarrower() {
            List<PeriodSummary> narrow =
                    request(RESULTS, List.of(), "2025-01-20..2025-01-21").byHitch();
            assertThat(narrow).hasSize(1);
            assertThat(narrow.get(0).owed()).isEqualTo(4);
        }
    }

    @Nested
    class SettledCount {

        @Test
        void leavesOutThePendingOnes() {
            assertThat(ObligationResult.settledCount(RESULTS)).isEqualTo(5);
        }
    }
}
