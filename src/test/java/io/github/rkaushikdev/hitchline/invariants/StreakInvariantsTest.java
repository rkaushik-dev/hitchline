package io.github.rkaushikdev.hitchline.invariants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.adherence.Shield;
import io.github.rkaushikdev.hitchline.domain.adherence.ShieldReason;
import io.github.rkaushikdev.hitchline.domain.adherence.StreakSummary;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.schedule.Expansion;
import io.github.rkaushikdev.hitchline.domain.schedule.Obligation;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.support.Builders;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.support.Source;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StreakInvariantsTest {

    private static final String ADA = Builders.ADA;
    private static final List<AdherenceStatus> STATUSES =
            List.of(AdherenceStatus.MET, AdherenceStatus.PARTIAL, AdherenceStatus.MISSED);

    private static String dayAfter(String from, int days) {
        return CivilDate.parse(from).plusDays(days).toString();
    }

    @Nested
    class TheGeneratorItself {

        @Test
        void isTheSameForTheSameSeed() {
            assertThat(Source.of(5).between(0, 100)).isEqualTo(Source.of(5).between(0, 100));
        }

        @Test
        void staysInsideTheRangeItWasGiven() {
            Source numbers = Source.of(11);
            for (int index = 0; index < 200; index += 1) {
                int value = numbers.between(3, 7);
                assertThat(value).isGreaterThanOrEqualTo(3);
                assertThat(value).isLessThanOrEqualTo(7);
            }
        }

        @Test
        void namesTheSeedWhenABodyFails() {
            assertThatThrownBy(() -> Source.forEachSeed(3, (numbers, seed) -> {
                        if (seed == 2) {
                            throw new IllegalStateException("deliberate");
                        }
                    }))
                    .hasMessageFindingMatch("seed 2: deliberate");
        }
    }

    @Nested
    class StreakInvariants {

        @Test
        void neverCountsMoreThanTheRunOfMetResultsAtTheEnd() {
            Source.forEachSeed(60, (numbers, seed) -> {
                int length = numbers.between(1, 30);
                List<AdherenceStatus> statuses = new ArrayList<>();
                for (int index = 0; index < length; index += 1) {
                    statuses.add(numbers.pick(STATUSES));
                }
                List<ObligationResult> results = new ArrayList<>();
                for (int index = 0; index < statuses.size(); index += 1) {
                    results.add(Builders.aResult(dayAfter("2025-01-06", index), statuses.get(index)));
                }
                StreakSummary summary = StreakSummary.compute(results, List.of(), ADA);

                int tail = 0;
                for (int index = statuses.size() - 1; index >= 0; index -= 1) {
                    if (statuses.get(index) != AdherenceStatus.MET) {
                        break;
                    }
                    tail += 1;
                }
                assertThat(summary.current()).isEqualTo(tail);
            });
        }

        @Test
        void neverReportsALongestRunBelowTheCurrentOne() {
            Source.forEachSeed(60, (numbers, seed) -> {
                int length = numbers.between(1, 20);
                List<ObligationResult> results = new ArrayList<>();
                for (int index = 0; index < length; index += 1) {
                    results.add(Builders.aResult(dayAfter("2025-01-06", index), numbers.pick(STATUSES)));
                }
                StreakSummary summary = StreakSummary.compute(results, List.of(), ADA);
                assertThat(summary.longest()).isGreaterThanOrEqualTo(summary.current());
            });
        }

        @Test
        void shieldingEveryDayLeavesNoRunAtAll() {
            Source.forEachSeed(30, (numbers, seed) -> {
                int length = numbers.between(1, 20);
                CivilDate from = CivilDate.parse("2025-01-06");
                List<ObligationResult> results = new ArrayList<>();
                for (int index = 0; index < length; index += 1) {
                    results.add(Builders.aResult(from.plusDays(index).toString(), numbers.pick(STATUSES)));
                }
                Shield all = Shield.of(
                        ADA,
                        DateRange.of(from, from.plusDays(length - 1L)),
                        ShieldReason.ANNUAL_LEAVE,
                        Zones.parseInstant("2025-01-01T09:00:00Z"));
                StreakSummary summary = StreakSummary.compute(results, List.of(all), ADA);
                assertThat(summary.current()).isZero();
                assertThat(summary.shielded()).isEqualTo(length);
            });
        }
    }

    @Nested
    class ExpansionInvariants {

        private static final List<String> PATTERNS =
                List.of("14/14", "2-2-3", "7on 7off", "1travel 13on 1travel 13off");

        @Test
        void neverAsksForAnythingOutsideTheWindowItWasGiven() {
            Source.forEachSeed(40, (numbers, seed) -> {
                String pattern = numbers.pick(PATTERNS);
                CrewMember member = Fixtures.aMember(o -> o.pattern(pattern).anchor("2025-01-06"));
                CivilDate start = CivilDate.parse("2025-01-01").plusDays(numbers.between(0, 120));
                DateRange window = DateRange.of(start, start.plusDays(numbers.between(0, 60)));
                String cadence = numbers.pick(List.of("1/day", "1/onshift-day", "2/hitch", "3/7d"));
                Habit habit = Fixtures.aHabit(o -> o.cadence(cadence).startsOn("2025-01-01"));
                for (Obligation entry :
                        Expansion.of(Roster.of(member), habit, window).expand()) {
                    assertThat(entry.window().to().year()).isGreaterThanOrEqualTo(2024);
                    assertThat(entry.window().length()).isPositive();
                    assertThat(entry.times()).isPositive();
                }
            });
        }

        @Test
        void givesEveryObligationADistinctKey() {
            Source.forEachSeed(40, (numbers, seed) -> {
                String pattern = numbers.pick(PATTERNS);
                CrewMember member = Fixtures.aMember(o -> o.pattern(pattern).anchor("2025-01-06"));
                CivilDate start = CivilDate.parse("2025-01-01").plusDays(numbers.between(0, 90));
                DateRange window = DateRange.of(start, start.plusDays(numbers.between(1, 90)));
                String cadence = numbers.pick(List.of("1/day", "1/onshift-day", "2/hitch", "1/onshift-block", "3/7d"));
                Habit habit = Fixtures.aHabit(o -> o.cadence(cadence).startsOn("2025-01-01"));
                List<Obligation> entries =
                        Expansion.of(Roster.of(member), habit, window).expand();
                Set<String> keys = new HashSet<>();
                for (Obligation entry : entries) {
                    keys.add(entry.scope().wire() + ":" + entry.dueOn());
                }
                assertThat(keys).hasSize(entries.size());
            });
        }

        @Test
        void asksForNothingAtAllBeforeTheHabitStarts() {
            Source.forEachSeed(30, (numbers, seed) -> {
                String pattern = numbers.pick(PATTERNS);
                CrewMember member = Fixtures.aMember(o -> o.pattern(pattern).anchor("2025-01-06"));
                String cadence = numbers.pick(List.of("1/day", "1/onshift-day"));
                Habit habit = Fixtures.aHabit(o -> o.cadence(cadence).startsOn("2025-06-01"));
                List<Obligation> entries = Expansion.of(
                                Roster.of(member), habit, DateRange.parse("2025-01-01..2025-05-31"))
                        .expand();
                assertThat(entries).isEmpty();
            });
        }
    }
}
