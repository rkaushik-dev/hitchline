package io.github.rkaushikdev.hitchline.domain.rotation;

import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.OFFSHIFT;
import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.ONSHIFT;
import static io.github.rkaushikdev.hitchline.domain.rotation.DayRole.TRAVEL;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RoleBlockTest {

    private static final CivilDate MONDAY = CivilDate.parse("2025-01-06");

    @Nested
    class RunsOfRoles {

        @Test
        void joinsNeighbouringDaysOfOneRole() {
            List<RoleBlock> runs = RoleBlock.runsOf(MONDAY, List.of(TRAVEL, ONSHIFT, ONSHIFT, ONSHIFT, OFFSHIFT));
            assertThat(runs)
                    .containsExactly(
                            new RoleBlock(TRAVEL, DateRange.parse("2025-01-06..2025-01-06")),
                            new RoleBlock(ONSHIFT, DateRange.parse("2025-01-07..2025-01-09")),
                            new RoleBlock(OFFSHIFT, DateRange.parse("2025-01-10..2025-01-10")));
        }

        @Test
        void keepsARoleThatComesBackAsItsOwnRun() {
            List<RoleBlock> runs = RoleBlock.runsOf(MONDAY, List.of(ONSHIFT, OFFSHIFT, ONSHIFT));
            assertThat(runs).extracting(RoleBlock::role).containsExactly(ONSHIFT, OFFSHIFT, ONSHIFT);
            assertThat(runs).extracting(RoleBlock::days).containsExactly(1, 1, 1);
        }

        @Test
        void answersOneRunForASingleRole() {
            assertThat(RoleBlock.runsOf(MONDAY, List.of(ONSHIFT, ONSHIFT)))
                    .containsExactly(new RoleBlock(ONSHIFT, DateRange.parse("2025-01-06..2025-01-07")));
        }

        @Test
        void answersNothingForNoDays() {
            assertThat(RoleBlock.runsOf(MONDAY, List.of())).isEmpty();
        }

        @Test
        void agreesWithThePatternBlocksOverAClippedWindow() {
            RotationAssignment rotation = RotationAssignment.of(RotationPattern.parse("14/14"), MONDAY);
            DateRange window = DateRange.parse("2025-01-10..2025-02-10");
            assertThat(rotation.blocksOver(window))
                    .containsExactly(
                            new RoleBlock(ONSHIFT, DateRange.parse("2025-01-10..2025-01-19")),
                            new RoleBlock(OFFSHIFT, DateRange.parse("2025-01-20..2025-02-02")),
                            new RoleBlock(ONSHIFT, DateRange.parse("2025-02-03..2025-02-10")));
        }
    }
}
