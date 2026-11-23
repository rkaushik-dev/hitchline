package io.github.rkaushikdev.hitchline.domain.crew;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.OverrideReason;
import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.rotation.RosterOverride;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PersonsOnBoardTest {

    private static final String ALPHA = "crw_0123456789ABCDEF";
    private static final CrewMember ADA = Fixtures.aMember();
    private static final CrewMember BO = Fixtures.aMember(
            o -> o.id("mem_FEDCBA9876543210").displayName("Bo Lindqvist").phase(14));
    private static final CrewMember CY = Fixtures.aMember(
            o -> o.id("mem_00000000000000CY").displayName("Cy Moreau").pattern("1travel 13on 14off"));

    private static final List<Membership> CREW = List.of(
            Membership.of(ALPHA, ADA.id(), CivilDate.parse("2025-01-01"), CrewRole.LEAD),
            Membership.of(ALPHA, BO.id(), CivilDate.parse("2025-01-01")),
            Membership.of(ALPHA, CY.id(), CivilDate.parse("2025-01-01")));

    private static final Map<String, Roster> ROSTERS =
            Map.of(ADA.id(), Roster.of(ADA), BO.id(), Roster.of(BO), CY.id(), Roster.of(CY));

    private static PersonsOnBoard on(String day, List<PersonsOnBoard> days) {
        return days.stream()
                .filter(entry -> entry.date().equals(CivilDate.parse(day)))
                .findFirst()
                .orElseThrow();
    }

    @Nested
    class Counting {

        private final List<PersonsOnBoard> days =
                PersonsOnBoard.count(CREW, ROSTERS, ALPHA, DateRange.parse("2025-01-06..2025-01-25"));

        @Test
        void countsOneLinePerDay() {
            assertThat(days).hasSize(20);
        }

        @Test
        void putsOnlyTheOnShiftMembersOnBoard() {
            assertThat(on("2025-01-06", days)).isEqualTo(new PersonsOnBoard(CivilDate.parse("2025-01-06"), 1, 1, 1));
            assertThat(on("2025-01-07", days).onBoard()).isEqualTo(2);
            assertThat(on("2025-01-20", days)).isEqualTo(new PersonsOnBoard(CivilDate.parse("2025-01-20"), 1, 0, 0));
        }

        @Test
        void flagsADayWithTheCrewOnBoardAndNoLead() {
            assertThat(on("2025-01-20", days).hasNoLead()).isTrue();
            assertThat(on("2025-01-07", days).hasNoLead()).isFalse();
            assertThat(on("2025-01-20", days).problems(Optional.empty())).containsExactly("no lead on board");
        }

        @Test
        void flagsADayOverTheBedCount() {
            PersonsOnBoard busy = on("2025-01-07", days);
            assertThat(busy.isOver(1)).isTrue();
            assertThat(busy.isOver(2)).isFalse();
            assertThat(busy.problems(Optional.of(1))).containsExactly("1 over the 1 beds");
            assertThat(busy.problems(Optional.of(2))).isEmpty();
        }

        @Test
        void neverFlagsAnEmptyInstallationForWantingALead() {
            PersonsOnBoard empty = new PersonsOnBoard(CivilDate.parse("2025-01-01"), 0, 0, 0);
            assertThat(empty.hasNoLead()).isFalse();
        }
    }

    @Nested
    class TheBooks {

        @Test
        void readsAHeldOverMemberAsStillOnBoard() {
            Roster held = Roster.of(
                    ADA,
                    List.of(RosterOverride.of(
                            ADA.id(),
                            DateRange.parse("2025-01-20..2025-01-21"),
                            DayRole.ONSHIFT,
                            OverrideReason.WEATHER,
                            Zones.parseInstant("2025-01-19T00:00:00Z"))));
            List<PersonsOnBoard> days = PersonsOnBoard.count(
                    CREW,
                    Map.of(ADA.id(), held, BO.id(), Roster.of(BO)),
                    ALPHA,
                    DateRange.parse("2025-01-20..2025-01-21"));
            assertThat(days).allMatch(day -> day.onBoard() == 2 && day.leads() == 1);
        }

        @Test
        void leavesOutSomebodyNotYetOnTheCrewOrWithNoRoster() {
            List<Membership> later = List.of(
                    Membership.of(ALPHA, ADA.id(), CivilDate.parse("2025-01-10"), CrewRole.LEAD),
                    Membership.of(ALPHA, BO.id(), CivilDate.parse("2025-01-01")));
            List<PersonsOnBoard> days = PersonsOnBoard.count(
                    later, Map.of(ADA.id(), Roster.of(ADA)), ALPHA, DateRange.parse("2025-01-08..2025-01-10"));
            assertThat(days).extracting(PersonsOnBoard::onBoard).containsExactly(0, 0, 1);
        }

        @Test
        void countsOnlyTheCrewAskedAbout() {
            List<Membership> elsewhere =
                    List.of(Membership.of("crw_FEDCBA9876543210", ADA.id(), CivilDate.parse("2025-01-01")));
            assertThat(PersonsOnBoard.count(elsewhere, ROSTERS, ALPHA, DateRange.parse("2025-01-07..2025-01-07")))
                    .extracting(PersonsOnBoard::onBoard)
                    .containsExactly(0);
        }
    }
}
