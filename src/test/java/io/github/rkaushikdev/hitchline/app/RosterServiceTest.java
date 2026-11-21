package io.github.rkaushikdev.hitchline.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.app.RosterService.HoldCommand;
import io.github.rkaushikdev.hitchline.app.RosterService.ReassignCommand;
import io.github.rkaushikdev.hitchline.app.RosterService.RosterDay;
import io.github.rkaushikdev.hitchline.app.RosterService.ShieldCommand;
import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.adherence.Shield;
import io.github.rkaushikdev.hitchline.domain.adherence.ShieldReason;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.OverrideReason;
import io.github.rkaushikdev.hitchline.domain.rotation.RoleSource;
import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.rotation.RosterOverride;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RosterServiceTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String BO = Ids.crewMemberId("mem_FEDCBA9876543210");

    private MemoryStore store;
    private RosterService service;

    @BeforeEach
    void create() {
        store = MemoryStore.create(Clocks.fixedClock("2025-01-10T09:00:00Z"), IdSources.sequentialIds());
        service = new RosterService(store);
        store.members().save(Fixtures.aMember());
    }

    private static HoldCommand hold(String range, DayRole role, OverrideReason reason) {
        return new HoldCommand(ADA, range, role, reason, Optional.empty());
    }

    private void holdOver() {
        service.hold(hold("2025-01-20..2025-01-22", DayRole.ONSHIFT, OverrideReason.HELD_OVER));
    }

    @Nested
    class ReadingARoster {

        @Test
        void assemblesTheMemberWithTheirOverrides() {
            holdOver();
            Roster target = service.rosterFor(ADA);
            assertThat(target.member().id()).isEqualTo(ADA);
            assertThat(target.overrides()).hasSize(1);
        }

        @Test
        void complainsAboutAMemberItDoesNotHold() {
            assertThatThrownBy(() -> service.rosterFor(BO)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class DaysOverAWindow {

        @Test
        void givesARoleAndAZoneForEachDay() {
            List<RosterDay> days = service.daysFor(ADA, "2025-01-19..2025-01-21");
            assertThat(days.stream().map(RosterDay::role))
                    .containsExactly(DayRole.ONSHIFT, DayRole.OFFSHIFT, DayRole.OFFSHIFT);
            assertThat(days.stream().map(RosterDay::zone))
                    .containsExactly("Europe/Oslo", "Europe/Lisbon", "Europe/Lisbon");
        }

        @Test
        void saysWhereARoleCameFrom() {
            holdOver();
            List<RosterDay> days = service.daysFor(ADA, "2025-01-19..2025-01-21");
            assertThat(days.stream().map(RosterDay::source))
                    .containsExactly(RoleSource.PATTERN, RoleSource.OVERRIDE, RoleSource.OVERRIDE);
            assertThat(days.get(1).describe()).isEqualTo("2025-01-20 onshift (override)");
        }
    }

    @Nested
    class Hitches {

        @Test
        void findsTheHitchADateSitsIn() {
            assertThat(service.hitchOn(ADA, "2025-01-30").toString()).isEqualTo("2025-01-06..2025-02-02");
        }
    }

    @Nested
    class HoldingSomebodyOver {

        @Test
        void storesTheOverrideWithTheClockReading() {
            RosterOverride entry = service.hold(new HoldCommand(
                    ADA,
                    "2025-01-20..2025-01-22",
                    DayRole.ONSHIFT,
                    OverrideReason.HELD_OVER,
                    Optional.of("relief weathered off")));
            assertThat(entry.enteredAt()).isEqualTo(store.clock().now());
            assertThat(entry.note()).isEqualTo("relief weathered off");
            assertThat(store.overrides().byMember(ADA)).hasSize(1);
        }

        @Test
        void refusesAnEntryThatClashesWithOneAlreadyStored() {
            holdOver();
            assertThatThrownBy(() ->
                            service.hold(hold("2025-01-22..2025-01-25", DayRole.OFFSHIFT, OverrideReason.MEDICAL)))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void leavesTheStoreUntouchedWhenItRefuses() {
            holdOver();
            assertThatThrownBy(() ->
                            service.hold(hold("2025-01-21..2025-01-25", DayRole.OFFSHIFT, OverrideReason.MEDICAL)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(store.overrides().byMember(ADA)).hasSize(1);
        }
    }

    @Nested
    class ShieldingDays {

        @Test
        void storesASelfDeclaredShield() {
            Shield entry = service.shieldDays(new ShieldCommand(
                    ADA, "2025-01-20..2025-01-26", ShieldReason.ANNUAL_LEAVE, Optional.empty(), Optional.empty()));
            assertThat(entry.grantedBy()).isEmpty();
            assertThat(store.shields().byMember(ADA)).hasSize(1);
        }

        @Test
        void needsAGranterForTheOtherReasons() {
            assertThatThrownBy(() -> service.shieldDays(new ShieldCommand(
                            ADA, "2025-02-03..2025-02-07", ShieldReason.TRAINING, Optional.empty(), Optional.empty())))
                    .hasMessageContaining("is needed to shield days");
        }
    }

    @Nested
    class ReassigningARotation {

        @Test
        void replacesThePatternAndStoresTheMember() {
            CrewMember updated =
                    service.reassignRotation(new ReassignCommand(ADA, "2-2-3", "2025-03-03", OptionalInt.empty()));
            assertThat(updated.rotation().pattern().cycle()).hasSize(14);
            assertThat(store.members()
                            .byId(ADA)
                            .orElseThrow()
                            .rotation()
                            .pattern()
                            .cycle())
                    .hasSize(14);
        }

        @Test
        void takesAPhaseForABackToBackCrew() {
            CrewMember updated =
                    service.reassignRotation(new ReassignCommand(ADA, "14/14", "2025-03-03", OptionalInt.of(14)));
            assertThat(updated.rotation().phase()).isEqualTo(14);
        }

        @Test
        void passesABadPatternStraightBack() {
            assertThatThrownBy(() -> service.reassignRotation(
                            new ReassignCommand(ADA, "two weeks on", "2025-03-03", OptionalInt.empty())))
                    .hasMessageContaining("cannot read the segment");
        }
    }
}
