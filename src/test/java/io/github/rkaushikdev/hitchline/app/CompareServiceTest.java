package io.github.rkaushikdev.hitchline.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.hitchline.app.CheckInsService.RecordCheckInCommand;
import io.github.rkaushikdev.hitchline.app.CompareService.CrewComparison;
import io.github.rkaushikdev.hitchline.app.CompareService.CrewLine;
import io.github.rkaushikdev.hitchline.app.RosterService.ShieldCommand;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.adherence.ShieldReason;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.crew.Crew;
import io.github.rkaushikdev.hitchline.domain.crew.CrewRole;
import io.github.rkaushikdev.hitchline.domain.crew.Visibility;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CompareServiceTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String BO = Ids.crewMemberId("mem_FEDCBA9876543210");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");
    private static final String STRETCHES = Ids.habitId("hab_FEDCBA9876543210");
    private static final String WINDOW = "2025-01-06..2025-01-19";

    private MemoryStore store;
    private CompareService service;
    private String alpha;

    @BeforeEach
    void create() {
        store = MemoryStore.create(Clocks.fixedClock("2025-01-20T09:00:00Z"), IdSources.sequentialIds());
        service = new CompareService(store);
        store.members().save(Fixtures.aMember());
        store.members().save(Fixtures.aMember(o -> o.id("mem_FEDCBA9876543210").displayName("Bo Lindqvist")));
        store.habits().save(Fixtures.aHabit(o -> o.cadence("1/onshift-day").startsOn("2025-01-06")));
        store.habits().save(Fixtures.aHabit(o -> o.id("hab_FEDCBA9876543210")
                .memberId("mem_FEDCBA9876543210")
                .cadence("1/onshift-day")
                .startsOn("2025-01-06")));
        CrewService crews = new CrewService(store);
        Crew crew = crews.create("Alpha production", "Grane B", "Europe/Oslo");
        alpha = crew.id();
        crews.enrol(crew.id(), ADA, "2025-01-01", Optional.of(CrewRole.LEAD));
        crews.enrol(crew.id(), BO, "2025-01-01", Optional.empty());
        CheckInsService checkIns = new CheckInsService(store);
        for (String day : new String[] {"2025-01-06", "2025-01-07"}) {
            checkIns.record(RecordCheckInCommand.of(TREADMILL)
                    .withAt(day + "T09:00:00Z")
                    .withSource(CheckInSource.SUPERVISOR));
        }
    }

    private static CrewLine lineOf(CrewComparison report, String memberId) {
        return report.lines().stream()
                .filter(line -> line.memberId().equals(memberId))
                .findFirst()
                .orElseThrow();
    }

    @Nested
    class ReadingYourOwnCrew {

        @Test
        void listsEverybodyInItOnTheDate() {
            CrewComparison report = service.crew(ADA, alpha, WINDOW, "2025-01-20");
            assertThat(report.lines().stream().map(CrewLine::displayName)).contains("Ada Vasquez");
            assertThat(report.lines()).hasSize(2);
        }

        @Test
        void countsYourOwnHabitsWhateverTheSharingSays() {
            CrewLine mine = lineOf(service.crew(ADA, alpha, WINDOW, "2025-01-20"), ADA);
            assertThat(mine.owed()).isEqualTo(14);
            assertThat(mine.met()).isEqualTo(2);
            assertThat(mine.hidden()).isZero();
        }

        @Test
        void leavesDaysOfLeaveOutOfAMembersLine() {
            new RosterService(store)
                    .shieldDays(new ShieldCommand(
                            ADA,
                            "2025-01-13..2025-01-19",
                            ShieldReason.ANNUAL_LEAVE,
                            Optional.empty(),
                            Optional.empty()));
            CrewLine mine = lineOf(service.crew(ADA, alpha, WINDOW, "2025-01-20"), ADA);
            assertThat(mine.owed()).isEqualTo(7);
            assertThat(mine.missed()).isEqualTo(5);
            assertThat(mine.rate()).isCloseTo(2 / 7.0, within(1e-9));
        }

        @Test
        void hidesAHabitSomebodyHasNotShared() {
            CrewLine theirs = lineOf(service.crew(ADA, alpha, WINDOW, "2025-01-20"), BO);
            assertThat(theirs.owed()).isZero();
            assertThat(theirs.hidden()).isEqualTo(1);
        }

        @Test
        void countsAHabitOnceItIsSharedWithTheLead() {
            new CrewService(store).setHabitVisibility(BO, STRETCHES, Visibility.LEAD_ONLY);
            CrewLine theirs = lineOf(service.crew(ADA, alpha, WINDOW, "2025-01-20"), BO);
            assertThat(theirs.owed()).isEqualTo(14);
            assertThat(theirs.hidden()).isZero();
        }

        @Test
        void stillHidesItFromACrewmateWhoIsNotTheLead() {
            new CrewService(store).setHabitVisibility(ADA, TREADMILL, Visibility.LEAD_ONLY);
            assertThat(lineOf(service.crew(BO, alpha, WINDOW, "2025-01-20"), ADA)
                            .hidden())
                    .isEqualTo(1);
        }
    }

    @Nested
    class TheCrewRate {

        @Test
        void isTheShareOfWhatTheViewerCanSeeThatLanded() {
            assertThat(service.crew(ADA, alpha, WINDOW, "2025-01-20").rate()).isCloseTo(2.0 / 14, within(1e-9));
        }

        @Test
        void countsOnlyWhatTheViewerCanSee() {
            // Bo sees their own habit and nothing of Ada's, so the crew rate is Bo's.
            CrewComparison report = service.crew(BO, alpha, WINDOW, "2025-01-20");
            assertThat(report.rate()).isEqualTo(0.0);
            assertThat(lineOf(report, ADA).owed()).isZero();
        }

        @Test
        void readsAWindowWithNothingOwedAsCleanRatherThanAsZero() {
            assertThat(service.crew(ADA, alpha, "2024-01-01..2024-01-31", "2025-01-20")
                            .rate())
                    .isEqualTo(1.0);
        }

        @Test
        void ordersTheBestFirst() {
            new CrewService(store).setVisibility(BO, Visibility.CREW);
            assertThat(service.crew(ADA, alpha, WINDOW, "2025-01-20")
                            .lines()
                            .get(0)
                            .memberId())
                    .isEqualTo(ADA);
        }
    }

    @Nested
    class BadRequests {

        @Test
        void complainsAboutACrewItDoesNotHold() {
            assertThatThrownBy(() -> service.crew(ADA, Ids.crewId("crw_9999999999999999"), WINDOW, "2025-01-20"))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void readsAnEmptyCrewOnADateNobodyWasInIt() {
            CrewComparison report = service.crew(ADA, alpha, WINDOW, "2024-01-01");
            assertThat(report.lines()).isEmpty();
            assertThat(report.rate()).isEqualTo(1.0);
        }
    }
}
