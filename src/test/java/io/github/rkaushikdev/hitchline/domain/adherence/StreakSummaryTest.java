package io.github.rkaushikdev.hitchline.domain.adherence;

import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.MET;
import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.MISSED;
import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.PARTIAL;
import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.PENDING;
import static io.github.rkaushikdev.hitchline.support.Builders.ADA;
import static io.github.rkaushikdev.hitchline.support.Builders.aResult;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StreakSummaryTest {

    private static final Shield LEAVE = Shield.of(
            ADA,
            DateRange.parse("2025-01-13..2025-01-15"),
            ShieldReason.ANNUAL_LEAVE,
            Zones.parseInstant("2025-01-04T10:00:00Z"));

    private static StreakSummary streak(List<ObligationResult> results) {
        return StreakSummary.compute(results, List.of(), ADA);
    }

    @Nested
    class AnEmptyRun {

        @Test
        void readsAsNothing() {
            assertThat(StreakSummary.compute(List.of(), List.of(), ADA)).isEqualTo(StreakSummary.NO_STREAK);
            assertThat(StreakSummary.NO_STREAK.describe()).isEqualTo("no run yet");
        }
    }

    @Nested
    class CountingARun {

        @Test
        void growsWithEachMetObligation() {
            StreakSummary summary =
                    streak(List.of(aResult("2025-01-10", MET), aResult("2025-01-11", MET), aResult("2025-01-12", MET)));
            assertThat(summary.current()).isEqualTo(3);
            assertThat(summary.longest()).isEqualTo(3);
            assertThat(summary.lastMetOn().map(CivilDate::toString)).hasValue("2025-01-12");
        }

        @Test
        void breaksOnAMissedObligationAndStartsAgain() {
            StreakSummary summary = streak(List.of(
                    aResult("2025-01-10", MET),
                    aResult("2025-01-11", MET),
                    aResult("2025-01-12", MISSED),
                    aResult("2025-01-13", MET)));
            assertThat(summary.current()).isEqualTo(1);
            assertThat(summary.longest()).isEqualTo(2);
            assertThat(summary.brokenOn().map(CivilDate::toString)).hasValue("2025-01-12");
        }

        @Test
        void breaksOnAPartialOneToo() {
            StreakSummary summary = streak(List.of(aResult("2025-01-10", MET), aResult("2025-01-11", PARTIAL)));
            assertThat(summary.current()).isZero();
            assertThat(summary.isBroken()).isTrue();
        }

        @Test
        void sortsTheResultsBeforeWalkingThem() {
            StreakSummary summary = streak(
                    List.of(aResult("2025-01-12", MET), aResult("2025-01-10", MET), aResult("2025-01-11", MISSED)));
            assertThat(summary.current()).isEqualTo(1);
            assertThat(summary.longest()).isEqualTo(1);
        }

        @Test
        void ignoresAnythingStillPending() {
            assertThat(streak(List.of(aResult("2025-01-10", MET), aResult("2025-01-11", PENDING)))
                            .current())
                    .isEqualTo(1);
        }
    }

    @Nested
    class ShieldedDays {

        @Test
        void stepsOverAShieldedObligationWithoutBreakingTheRun() {
            StreakSummary summary = StreakSummary.compute(
                    List.of(
                            aResult("2025-01-12", MET),
                            aResult("2025-01-13", MISSED),
                            aResult("2025-01-14", MISSED),
                            aResult("2025-01-16", MET)),
                    List.of(LEAVE),
                    ADA);
            assertThat(summary.current()).isEqualTo(2);
            assertThat(summary.shielded()).isEqualTo(2);
            assertThat(summary.brokenOn()).isEmpty();
        }

        @Test
        void doesNotLetAShieldedDayExtendTheRunEither() {
            StreakSummary summary = StreakSummary.compute(
                    List.of(aResult("2025-01-13", MET), aResult("2025-01-14", MET)), List.of(LEAVE), ADA);
            assertThat(summary.current()).isZero();
            assertThat(summary.shielded()).isEqualTo(2);
        }

        @Test
        void ignoresAShieldGrantedToSomebodyElse() {
            StreakSummary summary = StreakSummary.compute(
                    List.of(aResult("2025-01-13", MISSED), aResult("2025-01-14", MET)),
                    List.of(LEAVE),
                    Ids.crewMemberId("mem_FEDCBA9876543210"));
            assertThat(summary.current()).isEqualTo(1);
            assertThat(summary.shielded()).isZero();
        }
    }

    @Nested
    class Risk {

        @Test
        void isAtRiskWhenARunIsAliveAndSomethingIsOpen() {
            StreakSummary summary = streak(List.of(aResult("2025-01-10", MET)));
            assertThat(summary.isAtRisk(List.of(aResult("2025-01-11", PENDING))))
                    .isTrue();
        }

        @Test
        void isNotAtRiskWithNoRunToLose() {
            assertThat(StreakSummary.NO_STREAK.isAtRisk(List.of(aResult("2025-01-11", PENDING))))
                    .isFalse();
        }

        @Test
        void isNotAtRiskWhenNothingIsOpen() {
            StreakSummary summary = streak(List.of(aResult("2025-01-10", MET)));
            assertThat(summary.isAtRisk(List.of(aResult("2025-01-11", MET)))).isFalse();
        }
    }

    @Nested
    class DescribingARun {

        @Test
        void readsTheLiveRun() {
            StreakSummary summary = streak(List.of(aResult("2025-01-10", MET), aResult("2025-01-11", MET)));
            assertThat(summary.describe()).isEqualTo("2 in a row, best 2");
            assertThat(summary.bestRun()).isEqualTo(2);
        }

        @Test
        void readsABrokenRun() {
            StreakSummary summary = streak(List.of(aResult("2025-01-10", MET), aResult("2025-01-11", MISSED)));
            assertThat(summary.describe()).isEqualTo("run broken, best was 1");
        }
    }
}
