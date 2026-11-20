package io.github.rkaushikdev.hitchline.domain.adherence;

import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.MET;
import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.MISSED;
import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.PARTIAL;
import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.PENDING;
import static io.github.rkaushikdev.hitchline.support.Builders.aResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class QuotaTest {

    private static final Quota TWO_IN_SEVEN = Quota.of("weekly slip", 2, 7);

    @Nested
    class BuildingAQuota {

        @Test
        void keepsTheNameTheAllowanceAndTheWindow() {
            assertThat(TWO_IN_SEVEN).isEqualTo(new Quota("weekly slip", 2, 7, true));
        }

        @Test
        void refusesAnEmptyName() {
            assertThatThrownBy(() -> Quota.of("  ", 2, 7)).isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesANegativeAllowance() {
            assertThatThrownBy(() -> Quota.of("weekly", -1, 7)).hasMessageContaining("zero or more");
        }

        @Test
        void boundsTheWindow() {
            assertThatThrownBy(() -> Quota.of("weekly", 1, 0)).hasMessageContaining("between 1 and 365");
            assertThatThrownBy(() -> Quota.of("weekly", 1, 400)).hasMessageContaining("between 1 and 365");
        }

        @Test
        void describesItself() {
            assertThat(TWO_IN_SEVEN.describe()).isEqualTo("weekly slip: at most 2 misses or partials in 7 days");
            assertThat(Quota.of("hard", 0, 30, false).describe()).isEqualTo("hard: at most 0 misses in 30 days");
        }
    }

    @Nested
    class ReadingAQuota {

        @Test
        void isCleanWhenNothingWasDropped() {
            QuotaReading reading = TWO_IN_SEVEN.read(List.of(aResult("2025-01-06", MET)));
            assertThat(reading.isBreached()).isFalse();
            assertThat(reading.worst()).isZero();
            assertThat(reading.headroom()).isEqualTo(2);
        }

        @Test
        void isCleanAtTheAllowance() {
            QuotaReading reading =
                    TWO_IN_SEVEN.read(List.of(aResult("2025-01-06", MISSED), aResult("2025-01-07", MISSED)));
            assertThat(reading.isBreached()).isFalse();
            assertThat(reading.worst()).isEqualTo(2);
            assertThat(reading.headroom()).isZero();
        }

        @Test
        void bitesOneOverTheAllowance() {
            QuotaReading reading = TWO_IN_SEVEN.read(List.of(
                    aResult("2025-01-06", MISSED), aResult("2025-01-07", MISSED), aResult("2025-01-08", MISSED)));
            assertThat(reading.isBreached()).isTrue();
            assertThat(reading.breaches().get(0).counted()).isEqualTo(3);
        }

        @Test
        void doesNotJoinTwoStretchesThatAreFarEnoughApart() {
            QuotaReading reading = TWO_IN_SEVEN.read(List.of(
                    aResult("2025-01-06", MISSED),
                    aResult("2025-01-07", MISSED),
                    aResult("2025-02-06", MISSED),
                    aResult("2025-02-07", MISSED)));
            assertThat(reading.isBreached()).isFalse();
        }

        @Test
        void countsAPartialByDefaultAndNotWhenToldOtherwise() {
            List<ObligationResult> results = List.of(
                    aResult("2025-01-06", MISSED), aResult("2025-01-07", PARTIAL), aResult("2025-01-08", PARTIAL));
            assertThat(TWO_IN_SEVEN.read(results).isBreached()).isTrue();
            assertThat(Quota.of("hard misses", 2, 7, false).read(results).isBreached())
                    .isFalse();
        }

        @Test
        void ignoresAnythingStillPending() {
            assertThat(Quota.of("tight", 0, 7)
                            .read(List.of(aResult("2025-01-06", PENDING)))
                            .isBreached())
                    .isFalse();
        }

        @Test
        void reportsOneBreachForARunRatherThanOnePerDay() {
            QuotaReading reading = Quota.of("tight", 1, 7)
                    .read(List.of(
                            aResult("2025-01-06", MISSED),
                            aResult("2025-01-07", MISSED),
                            aResult("2025-01-08", MISSED)));
            assertThat(reading.breaches()).hasSize(1);
        }

        @Test
        void readsResultsInAnyOrder() {
            QuotaReading reading =
                    Quota.of("tight", 1, 7).read(List.of(aResult("2025-01-08", MISSED), aResult("2025-01-06", MISSED)));
            assertThat(reading.worst()).isEqualTo(2);
        }

        @Test
        void describesABreachWithItsWindow() {
            QuotaReading reading =
                    Quota.of("tight", 1, 7).read(List.of(aResult("2025-01-06", MISSED), aResult("2025-01-07", MISSED)));
            assertThat(reading.breaches().get(0).describe()).isEqualTo("tight broken 2025-01-06 to 2025-01-12 with 2");
        }
    }

    @Nested
    class AOneDayWindow {

        @Test
        void onlyEverSeesOneDayAtATime() {
            QuotaReading reading =
                    Quota.of("daily", 0, 1).read(List.of(aResult("2025-01-06", MISSED), aResult("2025-01-07", MISSED)));
            assertThat(reading.breaches()).hasSize(2);
            assertThat(reading.worst()).isEqualTo(1);
        }
    }
}
