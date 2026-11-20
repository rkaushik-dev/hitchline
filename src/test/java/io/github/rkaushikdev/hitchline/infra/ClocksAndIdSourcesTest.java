package io.github.rkaushikdev.hitchline.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.app.Clock;
import io.github.rkaushikdev.hitchline.app.IdSource;
import io.github.rkaushikdev.hitchline.domain.IdKind;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ClocksAndIdSourcesTest {

    @Nested
    class TheSystemClock {

        @Test
        void readsSomethingCloseToNow() {
            long before = System.currentTimeMillis();
            long reading = Clocks.systemClock().now().toEpochMilli();
            assertThat(reading).isGreaterThanOrEqualTo(before);
            assertThat(reading).isLessThan(before + 5_000);
        }
    }

    @Nested
    class AFixedClock {

        @Test
        void readsTheSameInstantEveryTime() {
            MutableClock clock = Clocks.fixedClock("2025-01-10T09:00:00Z");
            assertThat(Zones.formatInstant(clock.now())).isEqualTo("2025-01-10T09:00:00.000Z");
            assertThat(Zones.formatInstant(clock.now())).isEqualTo("2025-01-10T09:00:00.000Z");
        }

        @Test
        void takesAnInstantAsWellAsAString() {
            MutableClock clock = Clocks.fixedClock(Zones.parseInstant("2025-01-10T09:00:00Z"));
            assertThat(clock.now()).isEqualTo(Zones.parseInstant("2025-01-10T09:00:00Z"));
        }

        @Test
        void movesWhenToldTo() {
            MutableClock clock = Clocks.fixedClock("2025-01-10T09:00:00Z");
            clock.advance(3_600_000);
            assertThat(Zones.formatInstant(clock.now())).isEqualTo("2025-01-10T10:00:00.000Z");
            clock.set(Zones.parseInstant("2025-02-01T00:00:00Z"));
            assertThat(Zones.formatInstant(clock.now())).isEqualTo("2025-02-01T00:00:00.000Z");
        }

        @Test
        void refusesANonsenseStep() {
            assertThatThrownBy(() -> Clocks.fixedClock("2025-01-10T09:00:00Z").advance(Double.NaN))
                    .hasMessageContaining("finite number");
        }
    }

    @Nested
    class ATickingClock {

        @Test
        void movesOnByTheStepEachReading() {
            Clock clock = Clocks.tickingClock("2025-01-10T09:00:00Z", 60_000);
            assertThat(Zones.formatInstant(clock.now())).isEqualTo("2025-01-10T09:00:00.000Z");
            assertThat(Zones.formatInstant(clock.now())).isEqualTo("2025-01-10T09:01:00.000Z");
            assertThat(Zones.formatInstant(clock.now())).isEqualTo("2025-01-10T09:02:00.000Z");
        }

        @Test
        void defaultsToASecond() {
            Clock clock = Clocks.tickingClock("2025-01-10T09:00:00Z");
            clock.now();
            assertThat(Zones.formatInstant(clock.now())).isEqualTo("2025-01-10T09:00:01.000Z");
        }

        @Test
        void refusesAStepBelowOne() {
            assertThatThrownBy(() -> Clocks.tickingClock("2025-01-10T09:00:00Z", 0))
                    .hasMessageContaining("above zero");
        }
    }

    @Nested
    class NowToSecond {

        @Test
        void dropsTheMilliseconds() {
            MutableClock clock = Clocks.fixedClock(Zones.parseInstant("2025-01-10T09:00:00.750Z"));
            assertThat(Zones.formatInstant(Clocks.nowToSecond(clock))).isEqualTo("2025-01-10T09:00:00.000Z");
        }
    }

    @Nested
    class RandomIds {

        @Test
        void mintsIdsOfTheRightShape() {
            String minted = IdSources.randomIds().next(IdKind.HABIT);
            assertThat(Ids.isWellFormed(IdKind.HABIT, minted)).isTrue();
            assertThat(minted).startsWith("hab_");
        }

        @Test
        void doesNotRepeatItselfOverARun() {
            IdSource ids = IdSources.randomIds();
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < 200; index += 1) {
                seen.add(ids.next(IdKind.CHECK_IN));
            }
            assertThat(seen).hasSize(200);
        }
    }

    @Nested
    class SequentialIds {

        @Test
        void isStableForAGivenSeed() {
            assertThat(IdSources.sequentialIds(0).next(IdKind.HABIT))
                    .isEqualTo(IdSources.sequentialIds(0).next(IdKind.HABIT));
        }

        @Test
        void movesOnWithEachCall() {
            IdSource ids = IdSources.sequentialIds();
            String first = ids.next(IdKind.CREW);
            String second = ids.next(IdKind.CREW);
            assertThat(first).isNotEqualTo(second);
            assertThat(Ids.isWellFormed(IdKind.CREW, second)).isTrue();
        }

        @Test
        void keepsThePrefixForTheKindAskedFor() {
            IdSource ids = IdSources.sequentialIds();
            assertThat(ids.next(IdKind.CREW_MEMBER)).startsWith("mem_");
            assertThat(ids.next(IdKind.CHECK_IN)).startsWith("chk_");
        }
    }

    @Nested
    class ScriptedIds {

        @Test
        void handsBackTheListInOrder() {
            IdSource ids = IdSources.scriptedIds(List.of("hab_0000000000000001", "hab_0000000000000002"));
            assertThat(ids.next(IdKind.HABIT)).isEqualTo("hab_0000000000000001");
            assertThat(ids.next(IdKind.HABIT)).isEqualTo("hab_0000000000000002");
        }

        @Test
        void refusesOnceTheListRunsOut() {
            IdSource ids = IdSources.scriptedIds(List.of("hab_0000000000000001"));
            ids.next(IdKind.HABIT);
            assertThatThrownBy(() -> ids.next(IdKind.HABIT)).hasMessageContaining("ran out after 1 of them");
        }
    }
}
