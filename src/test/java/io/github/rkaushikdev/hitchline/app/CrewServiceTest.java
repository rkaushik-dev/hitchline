package io.github.rkaushikdev.hitchline.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.crew.Crew;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.crew.CrewRole;
import io.github.rkaushikdev.hitchline.domain.crew.Membership;
import io.github.rkaushikdev.hitchline.domain.crew.PersonsOnBoard;
import io.github.rkaushikdev.hitchline.domain.crew.SharingPreference;
import io.github.rkaushikdev.hitchline.domain.crew.Visibility;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.OverrideReason;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CrewServiceTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String BO = Ids.crewMemberId("mem_FEDCBA9876543210");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");

    private MemoryStore store;
    private CrewService service;

    @BeforeEach
    void create() {
        store = MemoryStore.create(Clocks.fixedClock("2025-01-10T09:00:00Z"), IdSources.sequentialIds());
        service = new CrewService(store);
        store.members().save(Fixtures.aMember());
        store.members().save(Fixtures.aMember(o -> o.id("mem_FEDCBA9876543210").displayName("Bo Lindqvist")));
    }

    private Crew alpha() {
        return service.create("Alpha production", "Grane B", "Europe/Oslo");
    }

    private Membership enrol(Crew crew, String memberId) {
        return service.enrol(crew.id(), memberId, "2025-01-01", Optional.empty());
    }

    private Membership enrolLead(Crew crew, String memberId) {
        return service.enrol(crew.id(), memberId, "2025-01-01", Optional.of(CrewRole.LEAD));
    }

    @Nested
    class CreatingACrew {

        @Test
        void mintsAnIdAndStoresIt() {
            Crew created = alpha();
            assertThat(created.id()).startsWith("crw_");
            assertThat(store.crews().byId(created.id())).hasValue(created);
        }

        @Test
        void validatesTheSite() {
            assertThatThrownBy(() -> service.create("Alpha", "Grane/B", "Europe/Oslo"))
                    .hasMessageContaining("not a usable site name");
        }
    }

    @Nested
    class Enrolling {

        @Test
        void addsAMembership() {
            assertThat(enrol(alpha(), ADA).role()).isEqualTo(CrewRole.MEMBER);
            assertThat(store.crews().memberships()).hasSize(1);
        }

        @Test
        void takesARole() {
            assertThat(enrolLead(alpha(), BO).role()).isEqualTo(CrewRole.LEAD);
        }

        @Test
        void refusesSomebodyAlreadyInAnotherCrewThatDay() {
            Crew first = alpha();
            Crew second = service.create("Bravo production", "Grane C", "Europe/Oslo");
            enrol(first, ADA);
            assertThatThrownBy(() -> service.enrol(second.id(), ADA, "2025-02-01", Optional.empty()))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void refusesACrewItDoesNotHold() {
            assertThatThrownBy(() ->
                            service.enrol(Ids.crewId("crw_9999999999999999"), ADA, "2025-01-01", Optional.empty()))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void refusesAMemberItDoesNotHold() {
            Crew crew = alpha();
            assertThatThrownBy(() -> enrol(crew, Ids.crewMemberId("mem_9999999999999999")))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Releasing {

        @Test
        void endsTheMembershipOnADate() {
            Crew crew = alpha();
            enrol(crew, ADA);
            assertThat(service.release(crew.id(), ADA, "2025-06-30").to().map(CivilDate::month))
                    .hasValue(6);
        }

        @Test
        void refusesAMembershipItDoesNotHold() {
            Crew crew = alpha();
            assertThatThrownBy(() -> service.release(crew.id(), ADA, "2025-06-30"))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class PersonsOnBoardOverAWindow {

        @Test
        void countsTheCrewOnBoardEachDayWithTheHoldsReadIn() {
            Crew crew = alpha();
            enrolLead(crew, ADA);
            enrol(crew, BO);
            new RosterService(store)
                    .hold(new RosterService.HoldCommand(
                            BO,
                            "2025-01-06..2025-01-06",
                            DayRole.ONSHIFT,
                            OverrideReason.CALLED_OUT,
                            Optional.empty()));
            List<PersonsOnBoard> days = service.personsOnBoard(crew.id(), "2025-01-05..2025-01-06");
            assertThat(days).extracting(PersonsOnBoard::onBoard).containsExactly(0, 2);
            assertThat(days.get(1).leads()).isEqualTo(1);
        }

        @Test
        void complainsAboutACrewItDoesNotHold() {
            assertThatThrownBy(() -> service.personsOnBoard("crw_9999999999999999", "2025-01-05..2025-01-06"))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class ReadingACrew {

        @Test
        void listsWhoWasInItOnADateByName() {
            Crew crew = alpha();
            enrol(crew, ADA);
            enrolLead(crew, BO);
            assertThat(service.membersOfCrew(crew.id(), "2025-02-01").stream().map(CrewMember::displayName))
                    .containsExactly("Ada Vasquez", "Bo Lindqvist");
        }

        @Test
        void dropsSomebodyOnceReleased() {
            Crew crew = alpha();
            enrol(crew, ADA);
            service.release(crew.id(), ADA, "2025-01-31");
            assertThat(service.membersOfCrew(crew.id(), "2025-02-01")).isEmpty();
        }

        @Test
        void findsTheCrewSomebodyWasIn() {
            Crew crew = alpha();
            enrol(crew, ADA);
            assertThat(service.crewFor(ADA, "2025-02-01").map(Crew::name)).hasValue("Alpha production");
            assertThat(service.crewFor(ADA, "2024-02-01")).isEmpty();
        }
    }

    @Nested
    class TheVisibility {

        @Test
        void defaultsToPrivate() {
            assertThat(service.visibilityFor(ADA).fallback()).isEqualTo(Visibility.PRIVATE);
        }

        @Test
        void storesAFallbackAndKeepsPerHabitSettings() {
            service.setHabitVisibility(ADA, TREADMILL, Visibility.CREW);
            SharingPreference updated = service.setVisibility(ADA, Visibility.LEAD_ONLY);
            assertThat(updated.fallback()).isEqualTo(Visibility.LEAD_ONLY);
            assertThat(updated.perHabit()).containsEntry(TREADMILL, Visibility.CREW);
        }

        @Test
        void refusesAMemberItDoesNotHold() {
            assertThatThrownBy(() -> service.setVisibility(Ids.crewMemberId("mem_9999999999999999"), Visibility.CREW))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class WhoMayRead {

        @BeforeEach
        void enrolBoth() {
            Crew crew = alpha();
            enrol(crew, ADA);
            enrolLead(crew, BO);
        }

        @Test
        void keepsAPrivateHabitToItsOwner() {
            assertThat(service.mayRead(BO, ADA, TREADMILL, "2025-02-01")).isFalse();
            assertThat(service.mayRead(ADA, ADA, TREADMILL, "2025-02-01")).isTrue();
        }

        @Test
        void opensALeadOnlyHabitToTheLead() {
            service.setHabitVisibility(ADA, TREADMILL, Visibility.LEAD_ONLY);
            assertThat(service.mayRead(BO, ADA, TREADMILL, "2025-02-01")).isTrue();
        }

        @Test
        void opensACrewHabitToACrewmate() {
            service.setVisibility(ADA, Visibility.CREW);
            assertThat(service.mayRead(BO, ADA, TREADMILL, "2025-02-01")).isTrue();
        }
    }
}
