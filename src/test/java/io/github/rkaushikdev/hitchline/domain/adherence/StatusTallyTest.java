package io.github.rkaushikdev.hitchline.domain.adherence;

import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.MET;
import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.MISSED;
import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.PARTIAL;
import static io.github.rkaushikdev.hitchline.domain.adherence.AdherenceStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.support.Builders;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StatusTallyTest {

    @Nested
    class Counting {

        private final StatusTally tally =
                StatusTally.of(Builders.aRun("2025-01-06", List.of(MET, PARTIAL, MISSED, MET, PENDING)));

        @Test
        void countsEachStatus() {
            assertThat(tally).isEqualTo(new StatusTally(2, 1, 1, 1));
        }

        @Test
        void addsUpWhatWasOwedAndWhatIsSettled() {
            assertThat(tally.owed()).isEqualTo(5);
            assertThat(tally.settled()).isEqualTo(4);
            assertThat(tally.closedShort()).isEqualTo(2);
        }

        @Test
        void ratesTheSettledOnly() {
            assertThat(tally.rate()).isEqualTo(0.5);
        }

        @Test
        void ratesNothingSettledAsOne() {
            assertThat(StatusTally.of(Builders.aRun("2025-01-06", List.of(PENDING)))
                            .rate())
                    .isEqualTo(1.0);
            assertThat(StatusTally.of(List.of())).isEqualTo(StatusTally.NONE);
            assertThat(StatusTally.NONE.rate()).isEqualTo(1.0);
        }

        @Test
        void agreesWithTheOlderHelpers() {
            List<ObligationResult> run = Builders.aRun("2025-01-06", List.of(MET, PARTIAL, MISSED, MET, PENDING));
            assertThat(ObligationResult.countByStatus(run)).isEqualTo(tally);
            assertThat(ObligationResult.adherenceRate(run)).isEqualTo(tally.rate());
            assertThat(ObligationResult.settledCount(run)).isEqualTo(4);
        }
    }

    @Nested
    class TheRoleSplit {

        @Test
        void keepsWhatClosedPartlyDoneApartFromWhatWasMissed() {
            RoleSplit split = RoleSplit.of(
                    DayRole.ONSHIFT,
                    StatusTally.of(Builders.aRun("2025-01-06", List.of(MET, PARTIAL, PARTIAL, MISSED))));
            assertThat(split.owed()).isEqualTo(4);
            assertThat(split.met()).isEqualTo(1);
            assertThat(split.partial()).isEqualTo(2);
            assertThat(split.missed()).isEqualTo(1);
            assertThat(split.rate()).isEqualTo(0.25);
        }
    }
}
