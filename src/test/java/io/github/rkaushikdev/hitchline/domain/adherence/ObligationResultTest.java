package io.github.rkaushikdev.hitchline.domain.adherence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.schedule.Expansion;
import io.github.rkaushikdev.hitchline.domain.schedule.Obligation;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ObligationResultTest {

    private static final CrewMember MEMBER = Fixtures.aMember();
    private static final Roster PLAIN = Roster.of(MEMBER);
    private static int next;

    private static CivilDate d(String text) {
        return CivilDate.parse(text);
    }

    private static CheckIn at(String iso) {
        return at(iso, OptionalDouble.empty());
    }

    private static CheckIn at(String iso, double reading) {
        return at(iso, OptionalDouble.of(reading));
    }

    private static CheckIn at(String iso, OptionalDouble reading) {
        next += 1;
        return CheckIn.builder(
                        Ids.checkInId("chk_" + Texts.padStart(Integer.toString(next), 16, '0')),
                        Ids.habitId("hab_0123456789ABCDEF"),
                        Ids.crewMemberId("mem_0123456789ABCDEF"),
                        Zones.parseInstant(iso),
                        Zones.parseInstant(iso),
                        CheckInSource.APP)
                .reading(reading)
                .build();
    }

    private static List<ResolvedCheckIn> resolve(CheckIn... entries) {
        return ResolvedCheckIn.resolveAll(MEMBER, List.of(entries));
    }

    private record Expanded(Habit habit, List<Obligation> entries) {}

    private static Expanded obligationsFor(String cadence, String window, String target) {
        Habit habit = Fixtures.aHabit(o -> o.cadence(cadence).target(target));
        return new Expanded(
                habit, Expansion.of(PLAIN, habit, DateRange.parse(window)).expand());
    }

    @Nested
    class ADailyObligation {

        private final Expanded daily = obligationsFor("1/day", "2025-01-10..2025-01-10", null);

        @Test
        void isMetByOneCheckIn() {
            ObligationResult result = ObligationResult.evaluate(
                    daily.habit(), daily.entries().get(0), resolve(at("2025-01-10T09:00:00Z")), d("2025-01-11"));
            assertThat(result.status()).isEqualTo(AdherenceStatus.MET);
            assertThat(result.count()).isEqualTo(1);
            assertThat(result.progress()).isEqualTo(1.0);
        }

        @Test
        void isMissedWhenTheDayPassedWithNothing() {
            ObligationResult result =
                    ObligationResult.evaluate(daily.habit(), daily.entries().get(0), List.of(), d("2025-01-11"));
            assertThat(result.status()).isEqualTo(AdherenceStatus.MISSED);
            assertThat(result.progress()).isEqualTo(0.0);
        }

        @Test
        void isPendingWhileTheDayIsStillOpen() {
            ObligationResult result =
                    ObligationResult.evaluate(daily.habit(), daily.entries().get(0), List.of(), d("2025-01-10"));
            assertThat(result.status()).isEqualTo(AdherenceStatus.PENDING);
        }

        @Test
        void ignoresACheckInForAnotherHabit() {
            List<ResolvedCheckIn> other = resolve(at("2025-01-10T09:00:00Z")).stream()
                    .map(item -> {
                        CheckIn entry = item.checkIn();
                        CheckIn moved = new CheckIn(
                                entry.id(),
                                Ids.habitId("hab_FEDCBA9876543210"),
                                entry.memberId(),
                                entry.at(),
                                entry.recordedAt(),
                                entry.reading(),
                                entry.note(),
                                entry.source(),
                                entry.idempotencyKey(),
                                entry.voidedAt());
                        return new ResolvedCheckIn(moved, item.day());
                    })
                    .toList();
            ObligationResult result =
                    ObligationResult.evaluate(daily.habit(), daily.entries().get(0), other, d("2025-01-11"));
            assertThat(result.count()).isZero();
        }
    }

    @Nested
    class AnObligationAskingForSeveralTimes {

        private final Expanded hitch = obligationsFor("3/hitch", "2025-01-06..2025-02-02", null);

        @Test
        void isPartialWhenTheWindowClosedShort() {
            ObligationResult result = ObligationResult.evaluate(
                    hitch.habit(),
                    hitch.entries().get(0),
                    resolve(at("2025-01-08T09:00:00Z"), at("2025-01-09T09:00:00Z")),
                    d("2025-02-03"));
            assertThat(result.status()).isEqualTo(AdherenceStatus.PARTIAL);
            assertThat(result.count()).isEqualTo(2);
            assertThat(result.progress()).isCloseTo(2.0 / 3, within(1e-9));
        }

        @Test
        void isMetOnceTheCountIsReachedAnywhereInTheWindow() {
            ObligationResult result = ObligationResult.evaluate(
                    hitch.habit(),
                    hitch.entries().get(0),
                    resolve(at("2025-01-08T09:00:00Z"), at("2025-01-09T09:00:00Z"), at("2025-01-30T09:00:00Z")),
                    d("2025-02-03"));
            assertThat(result.status()).isEqualTo(AdherenceStatus.MET);
        }

        @Test
        void doesNotCountACheckInFromOutsideTheWindow() {
            ObligationResult result = ObligationResult.evaluate(
                    hitch.habit(), hitch.entries().get(0), resolve(at("2025-02-04T09:00:00Z")), d("2025-02-05"));
            assertThat(result.count()).isZero();
        }
    }

    @Nested
    class ATargetedObligation {

        private final Expanded targeted = obligationsFor("1/day", "2025-01-10..2025-01-10", "at least 30 minutes");

        @Test
        void addsTheReadingsAcrossTheWindow() {
            ObligationResult result = ObligationResult.evaluate(
                    targeted.habit(),
                    targeted.entries().get(0),
                    resolve(at("2025-01-10T09:00:00Z", 20), at("2025-01-10T18:00:00Z", 15)),
                    d("2025-01-11"));
            assertThat(result.total()).hasValue(35);
            assertThat(result.status()).isEqualTo(AdherenceStatus.MET);
        }

        @Test
        void isPartialWhenTheCountIsThereButTheAmountIsNot() {
            ObligationResult result = ObligationResult.evaluate(
                    targeted.habit(),
                    targeted.entries().get(0),
                    resolve(at("2025-01-10T09:00:00Z", 10)),
                    d("2025-01-11"));
            assertThat(result.status()).isEqualTo(AdherenceStatus.PARTIAL);
            assertThat(result.progress()).isCloseTo(1.0 / 3, within(1e-9));
        }

        @Test
        void treatsACheckInWithNoReadingAsNothingTowardsTheTarget() {
            ObligationResult result = ObligationResult.evaluate(
                    targeted.habit(), targeted.entries().get(0), resolve(at("2025-01-10T09:00:00Z")), d("2025-01-11"));
            assertThat(result.total()).hasValue(0);
            assertThat(result.status()).isEqualTo(AdherenceStatus.PARTIAL);
        }
    }

    @Nested
    class ReadingARunOfResults {

        private final Expanded run = obligationsFor("1/day", "2025-01-10..2025-01-13", null);
        private final List<ObligationResult> results = ObligationResult.evaluateAll(
                run.habit(),
                run.entries(),
                resolve(at("2025-01-10T09:00:00Z"), at("2025-01-12T09:00:00Z")),
                d("2025-01-13"));

        @Test
        void countsEachStatus() {
            assertThat(ObligationResult.countByStatus(results)).isEqualTo(new StatusTally(2, 0, 1, 1));
        }

        @Test
        void knowsWhichOnesAreSettled() {
            assertThat(results.stream().filter(ObligationResult::isSettled)).hasSize(3);
        }

        @Test
        void ratesOnlyTheSettledOnes() {
            assertThat(ObligationResult.adherenceRate(results)).isCloseTo(2.0 / 3, within(1e-9));
        }

        @Test
        void ratesAnEmptyRunAsClean() {
            assertThat(ObligationResult.adherenceRate(List.of())).isEqualTo(1.0);
        }
    }
}
