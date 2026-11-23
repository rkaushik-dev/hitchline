package io.github.rkaushikdev.hitchline.domain.adherence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.schedule.Obligation;
import io.github.rkaushikdev.hitchline.domain.schedule.ObligationScope;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ProrationTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String BO = Ids.crewMemberId("mem_FEDCBA9876543210");
    private static final String GYM = Ids.habitId("hab_0123456789ABCDEF");
    private static final Obligation HITCH =
            new Obligation(GYM, ADA, ObligationScope.HITCH, DateRange.parse("2025-01-06..2025-02-02"), 3);

    private static Shield sick(String member, String range) {
        return Shield.of(member, DateRange.parse(range), ShieldReason.SICK, Zones.parseInstant("2025-01-01T00:00:00Z"));
    }

    @Nested
    class AHitchPartlyShielded {

        @Test
        void asksForHalfAsMuchWhenHalfTheHitchIsShielded() {
            Obligation cut = Proration.prorate(HITCH, List.of(sick(ADA, "2025-01-06..2025-01-19")), ADA);
            assertThat(cut.times()).isEqualTo(2);
            assertThat(cut.window()).isEqualTo(HITCH.window());
            assertThat(cut.key()).isEqualTo(HITCH.key());
        }

        @Test
        void roundsUpSoAPartHitchStillAsksForSomething() {
            assertThat(Proration.prorate(HITCH, List.of(sick(ADA, "2025-01-13..2025-01-19")), ADA)
                            .times())
                    .isEqualTo(3);
            assertThat(Proration.prorate(HITCH, List.of(sick(ADA, "2025-01-06..2025-02-01")), ADA)
                            .times())
                    .isEqualTo(1);
        }

        @Test
        void countsOverlappingShieldsOnce() {
            List<Shield> twice = List.of(sick(ADA, "2025-01-06..2025-01-19"), sick(ADA, "2025-01-10..2025-01-19"));
            assertThat(Proration.prorate(HITCH, twice, ADA).times()).isEqualTo(2);
        }
    }

    @Nested
    class LeftAlone {

        @Test
        void leavesAnObligationNobodyShielded() {
            assertThat(Proration.prorate(HITCH, List.of(), ADA)).isSameAs(HITCH);
            assertThat(Proration.prorate(HITCH, List.of(sick(BO, "2025-01-06..2025-01-19")), ADA))
                    .isSameAs(HITCH);
        }

        @Test
        void leavesOneShieldedEndToEnd() {
            assertThat(Proration.prorate(HITCH, List.of(sick(ADA, "2025-01-01..2025-02-28")), ADA))
                    .isSameAs(HITCH);
        }

        @Test
        void leavesADayAlone() {
            Obligation day =
                    new Obligation(GYM, ADA, ObligationScope.DAY, DateRange.parse("2025-01-06..2025-01-07"), 2);
            assertThat(Proration.prorate(day, List.of(sick(ADA, "2025-01-06..2025-01-06")), ADA))
                    .isSameAs(day);
        }

        @Test
        void proratesEveryObligationInOrder() {
            Obligation next =
                    new Obligation(GYM, ADA, ObligationScope.WINDOW, DateRange.parse("2025-02-03..2025-02-16"), 4);
            List<Obligation> cut =
                    Proration.prorateAll(List.of(HITCH, next), List.of(sick(ADA, "2025-02-03..2025-02-09")), ADA);
            assertThat(cut).extracting(Obligation::times).containsExactly(3, 2);
        }
    }
}
