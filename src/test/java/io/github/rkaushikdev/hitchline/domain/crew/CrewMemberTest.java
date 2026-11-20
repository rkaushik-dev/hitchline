package io.github.rkaushikdev.hitchline.domain.crew;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationAssignment;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationPattern;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CrewMemberTest {

    private static final RotationAssignment ROTATION =
            RotationAssignment.of(RotationPattern.parse("14/14"), CivilDate.parse("2025-01-06"));

    private static CrewMember withName(String displayName) {
        return withZones(displayName, "Europe/Lisbon", "Europe/Oslo");
    }

    private static CrewMember withZones(String displayName, String homeZone, String siteZone) {
        return CrewMember.of(
                Ids.crewMemberId("mem_0123456789ABCDEF"),
                displayName,
                homeZone,
                siteZone,
                ROTATION,
                CivilDate.parse("2024-11-01"));
    }

    private static CrewMember ada() {
        return withName("Ada Vasquez");
    }

    @Nested
    class Names {

        @Test
        void squeezesRunsOfWhitespace() {
            assertThat(CrewMember.normaliseName("  Ada   Vasquez ")).isEqualTo("Ada Vasquez");
        }

        @Test
        void rejectsANameThatIsTooShortOrTooLong() {
            assertThatThrownBy(() -> CrewMember.checkName("A")).hasMessageContaining("at least 2 characters");
            assertThatThrownBy(() -> CrewMember.checkName("x".repeat(81)))
                    .hasMessageContaining("at most 80 characters");
        }

        @Test
        void rejectsControlCharacters() {
            assertThatThrownBy(() -> CrewMember.checkName("Ada\u0000Vasquez"))
                    .hasMessageContaining("control characters");
            assertThatThrownBy(() -> CrewMember.checkName("Ada\u0007Vasquez"))
                    .hasMessageContaining("control characters");
        }

        @Test
        void keepsLettersOutsideTheAsciiRange() {
            assertThat(CrewMember.checkName("Bjørn Håland")).isEqualTo("Bjørn Håland");
        }
    }

    @Nested
    class CreatingAMember {

        @Test
        void defaultsToActive() {
            CrewMember member = ada();
            assertThat(member.status()).isEqualTo(MemberStatus.ACTIVE);
            assertThat(member.isActive()).isTrue();
        }

        @Test
        void keepsTheHomeAndSiteZonesApart() {
            CrewMember member = ada();
            assertThat(member.homeZone()).isEqualTo("Europe/Lisbon");
            assertThat(member.siteZone()).isEqualTo("Europe/Oslo");
        }

        @Test
        void rejectsAZoneThePlatformDoesNotKnow() {
            assertThatThrownBy(() -> withZones("Ada Vasquez", "Europe/Lisbon", "North Sea/Alpha"))
                    .hasMessageContaining("not a timezone");
        }

        @Test
        void tidiesTheNameOnTheWayIn() {
            assertThat(withName(" Ada  Vasquez ").displayName()).isEqualTo("Ada Vasquez");
        }
    }

    @Nested
    class StatusMoves {

        private final CrewMember member = ada();

        @Test
        void suspendsAndReinstates() {
            CrewMember suspended = member.moveTo(MemberStatus.SUSPENDED);
            assertThat(suspended.status()).isEqualTo(MemberStatus.SUSPENDED);
            assertThat(suspended.moveTo(MemberStatus.ACTIVE).status()).isEqualTo(MemberStatus.ACTIVE);
        }

        @Test
        void treatsAMoveToTheSameStatusAsANoOp() {
            assertThat(member.moveTo(MemberStatus.ACTIVE)).isSameAs(member);
        }

        @Test
        void refusesToBringBackSomebodyWhoHasLeft() {
            CrewMember departed = member.moveTo(MemberStatus.DEPARTED);
            assertThatThrownBy(() -> departed.moveTo(MemberStatus.ACTIVE))
                    .hasMessageContaining("cannot go from departed to active");
        }
    }

    @Nested
    class Edits {

        private final CrewMember member = ada();

        @Test
        void renames() {
            assertThat(member.rename("Ada Nkemdirim").displayName()).isEqualTo("Ada Nkemdirim");
        }

        @Test
        void movesSite() {
            assertThat(member.relocate("America/Halifax").siteZone()).isEqualTo("America/Halifax");
        }

        @Test
        void reassignsTheRotation() {
            RotationAssignment swung =
                    RotationAssignment.of(RotationPattern.parse("2-2-3"), CivilDate.parse("2025-03-01"));
            assertThat(member.reassign(swung).rotation()).isSameAs(swung);
        }

        @Test
        void refusesToReassignSomebodyWhoHasLeft() {
            CrewMember departed = member.moveTo(MemberStatus.DEPARTED);
            assertThatThrownBy(() -> departed.reassign(ROTATION)).hasMessageContaining("somebody who has left");
        }
    }

    @Nested
    class Initials {

        @Test
        void takesTheFirstAndLastWord() {
            assertThat(ada().initials()).isEqualTo("AV");
        }

        @Test
        void handlesAThreePartName() {
            assertThat(withName("Ada Maria Vasquez").initials()).isEqualTo("AV");
        }

        @Test
        void handlesASingleWordName() {
            assertThat(withName("Ada").initials()).isEqualTo("A");
        }
    }
}
