package io.github.rkaushikdev.hitchline.domain.crew;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CrewTest {

    private static final String ALPHA = Ids.crewId("crw_0123456789ABCDEF");
    private static final String BRAVO = Ids.crewId("crw_FEDCBA9876543210");
    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String BO = Ids.crewMemberId("mem_FEDCBA9876543210");

    private static CivilDate d(String text) {
        return CivilDate.parse(text);
    }

    private static Crew aCrew() {
        return aCrew("Alpha production", "Grane B", "Europe/Oslo");
    }

    private static Crew aCrew(String name, String site, String siteZone) {
        return Crew.of(ALPHA, name, site, siteZone);
    }

    /** The default membership: Ada in Alpha from New Year, as a plain member. */
    private static Membership aMembership() {
        return Membership.of(ALPHA, ADA, d("2025-01-01"));
    }

    private static Membership ending(String to) {
        return new Membership(ALPHA, ADA, CrewRole.MEMBER, d("2025-01-01"), Optional.of(d(to)));
    }

    @Nested
    class CreatingACrew {

        @Test
        void tidiesTheName() {
            assertThat(aCrew("  Alpha   production ", "Grane B", "Europe/Oslo").name())
                    .isEqualTo("Alpha production");
        }

        @Test
        void boundsTheName() {
            assertThatThrownBy(() -> aCrew("A", "Grane B", "Europe/Oslo"))
                    .hasMessageContaining("must be 2 to 60 characters");
            assertThatThrownBy(() -> aCrew("x".repeat(61), "Grane B", "Europe/Oslo"))
                    .hasMessageContaining("must be 2 to 60 characters");
        }

        @Test
        void checksTheSiteName() {
            assertThatThrownBy(() -> aCrew("Alpha production", "", "Europe/Oslo"))
                    .hasMessageContaining("not a usable site name");
            assertThatThrownBy(() -> aCrew("Alpha production", "Grane/B", "Europe/Oslo"))
                    .hasMessageContaining("not a usable site name");
            assertThat(aCrew("Alpha production", "Grane B", "Europe/Oslo").site())
                    .isEqualTo("Grane B");
        }

        @Test
        void checksTheZone() {
            assertThatThrownBy(() -> aCrew("Alpha production", "Grane B", "North Sea/Grane"))
                    .hasMessageContaining("not a timezone");
        }
    }

    @Nested
    class TheMembership {

        @Test
        void defaultsToPlainMemberWithNoEnd() {
            Membership entry = aMembership();
            assertThat(entry.role()).isEqualTo(CrewRole.MEMBER);
            assertThat(entry.to()).isEmpty();
        }

        @Test
        void refusesAnEndBeforeTheStart() {
            assertThatThrownBy(() -> ending("2024-12-01")).hasMessageContaining("ends before the membership");
        }

        @Test
        void endsOnADate() {
            assertThat(aMembership().endOn(d("2025-06-30")).to().map(CivilDate::toString))
                    .hasValue("2025-06-30");
        }

        @Test
        void refusesToEndBeforeItBegan() {
            assertThatThrownBy(() -> aMembership().endOn(d("2024-06-30")))
                    .hasMessageContaining("before the membership");
        }
    }

    @Nested
    class ReadingACrewOnADate {

        private final List<Membership> memberships = List.of(
                ending("2025-03-31"),
                Membership.of(ALPHA, BO, d("2025-02-01"), CrewRole.LEAD),
                Membership.of(BRAVO, ADA, d("2025-04-01")));

        @Test
        void listsWhoWasInItThatDay() {
            assertThat(Membership.membersOn(memberships, ALPHA, d("2025-01-15")).stream()
                            .map(Membership::memberId))
                    .containsExactly(ADA);
            assertThat(Membership.membersOn(memberships, ALPHA, d("2025-02-15")))
                    .hasSize(2);
        }

        @Test
        void dropsSomebodyOnceTheirMembershipEnded() {
            assertThat(Membership.membersOn(memberships, ALPHA, d("2025-04-15")).stream()
                            .map(Membership::memberId))
                    .containsExactly(BO);
        }

        @Test
        void findsTheCrewSomebodyWasIn() {
            assertThat(Membership.crewOf(memberships, ADA, d("2025-02-01")).map(Membership::crewId))
                    .hasValue(ALPHA);
            assertThat(Membership.crewOf(memberships, ADA, d("2025-05-01")).map(Membership::crewId))
                    .hasValue(BRAVO);
            assertThat(Membership.crewOf(memberships, ADA, d("2024-01-01"))).isEmpty();
        }

        @Test
        void listsTheLeads() {
            assertThat(Membership.leadsOf(memberships, ALPHA, d("2025-02-15")).stream()
                            .map(Membership::memberId))
                    .containsExactly(BO);
            assertThat(Membership.leadsOf(memberships, ALPHA, d("2025-01-15"))).isEmpty();
        }
    }

    @Nested
    class OneCrewAtATime {

        @Test
        void acceptsMembershipsThatFollowOneAnother() {
            assertThatCode(() -> Membership.checkOneCrewPerDay(
                            List.of(ending("2025-03-31"), Membership.of(BRAVO, ADA, d("2025-04-01")))))
                    .doesNotThrowAnyException();
        }

        @Test
        void refusesTwoCrewsOnTheSameDay() {
            assertThatThrownBy(() -> Membership.checkOneCrewPerDay(
                            List.of(ending("2025-04-30"), Membership.of(BRAVO, ADA, d("2025-04-01")))))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void refusesTwoOpenEndedMemberships() {
            assertThatThrownBy(() -> Membership.checkOneCrewPerDay(
                            List.of(aMembership(), Membership.of(BRAVO, ADA, d("2026-01-01")))))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void doesNotMindTwoPeopleSharingACrew() {
            assertThatCode(() -> Membership.checkOneCrewPerDay(
                            List.of(aMembership(), Membership.of(ALPHA, BO, d("2025-01-01")))))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class RequireCrew {

        @Test
        void findsOneById() {
            assertThat(Crew.require(List.of(aCrew()), ALPHA).name()).isEqualTo("Alpha production");
        }

        @Test
        void complainsAboutOneItDoesNotHold() {
            assertThatThrownBy(() -> Crew.require(List.of(aCrew()), BRAVO)).isInstanceOf(NotFoundException.class);
        }
    }
}
