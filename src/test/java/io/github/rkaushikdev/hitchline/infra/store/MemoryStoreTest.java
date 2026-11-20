package io.github.rkaushikdev.hitchline.infra.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.IdKind;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.adherence.Shield;
import io.github.rkaushikdev.hitchline.domain.adherence.ShieldReason;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.crew.Crew;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.crew.Membership;
import io.github.rkaushikdev.hitchline.domain.crew.SharingPreference;
import io.github.rkaushikdev.hitchline.domain.crew.Visibility;
import io.github.rkaushikdev.hitchline.domain.nudge.NudgePolicy;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.OverrideReason;
import io.github.rkaushikdev.hitchline.domain.rotation.RosterOverride;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MemoryStoreTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String BO = Ids.crewMemberId("mem_FEDCBA9876543210");
    private static final String ALPHA = Ids.crewId("crw_0123456789ABCDEF");

    private MemoryStore store;

    @BeforeEach
    void create() {
        store = MemoryStore.create(Clocks.fixedClock("2025-01-10T09:00:00Z"), IdSources.sequentialIds());
    }

    private static CheckIn anEntry(String id, String at) {
        return anEntry(id, at, "hab_0123456789ABCDEF");
    }

    private static CheckIn anEntry(String id, String at, String habit) {
        return CheckIn.builder(
                        Ids.checkInId(id),
                        Ids.habitId(habit),
                        ADA,
                        Zones.parseInstant(at),
                        Zones.parseInstant(at),
                        CheckInSource.APP)
                .build();
    }

    private static DateRange r(String text) {
        return DateRange.parse(text);
    }

    @Nested
    class Members {

        @Test
        void savesAndReadsBack() {
            store.members().save(Fixtures.aMember());
            assertThat(store.members().byId(ADA).map(CrewMember::displayName)).hasValue("Ada Vasquez");
        }

        @Test
        void answersNothingForAnIdItDoesNotHold() {
            assertThat(store.members().byId(BO)).isEmpty();
        }

        @Test
        void overwritesOnASecondSave() {
            store.members().save(Fixtures.aMember());
            store.members().save(Fixtures.aMember(o -> o.displayName("Ada Nkemdirim")));
            assertThat(store.members().all()).hasSize(1);
        }
    }

    @Nested
    class Habits {

        @Test
        void filtersToOneMember() {
            store.habits().save(Fixtures.aHabit());
            store.habits()
                    .save(Fixtures.aHabit(o -> o.id("hab_FEDCBA9876543210").memberId(BO)));
            assertThat(store.habits().byMember(ADA)).hasSize(1);
            assertThat(store.habits().byMember(BO)).hasSize(1);
        }
    }

    @Nested
    class CheckIns {

        @Test
        void refusesTheSameIdTwice() {
            store.checkIns().add(anEntry("chk_0000000000000001", "2025-01-10T07:00:00Z"));
            assertThatThrownBy(() -> store.checkIns().add(anEntry("chk_0000000000000001", "2025-01-10T07:00:00Z")))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void replacesOneItHolds() {
            CheckIn entry = anEntry("chk_0000000000000001", "2025-01-10T07:00:00Z");
            store.checkIns().add(entry);
            store.checkIns().replace(entry.withNote("amended"));
            assertThat(store.checkIns().byId("chk_0000000000000001").map(CheckIn::note))
                    .hasValue("amended");
        }

        @Test
        void refusesToReplaceOneItDoesNotHold() {
            assertThatThrownBy(() -> store.checkIns().replace(anEntry("chk_0000000000000009", "2025-01-10T07:00:00Z")))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void filtersByHabit() {
            store.checkIns().add(anEntry("chk_0000000000000001", "2025-01-10T07:00:00Z"));
            store.checkIns().add(anEntry("chk_0000000000000002", "2025-01-10T08:00:00Z", "hab_FEDCBA9876543210"));
            assertThat(store.checkIns().byHabit(Ids.habitId("hab_0123456789ABCDEF")))
                    .hasSize(1);
        }

        @Test
        void filtersByWindowWhenOneIsGiven() {
            store.members().save(Fixtures.aMember());
            store.checkIns().add(anEntry("chk_0000000000000001", "2025-01-10T07:00:00Z"));
            store.checkIns().add(anEntry("chk_0000000000000002", "2025-01-20T07:00:00Z"));
            assertThat(store.checkIns().byMember(ADA, r("2025-01-01..2025-01-15")))
                    .hasSize(1);
            assertThat(store.checkIns().byMember(ADA)).hasSize(2);
        }
    }

    @Nested
    class OverridesAndShields {

        @Test
        void keepsEachPersonEntriesApart() {
            store.overrides()
                    .add(RosterOverride.of(
                            ADA,
                            r("2025-01-20..2025-01-22"),
                            DayRole.ONSHIFT,
                            OverrideReason.HELD_OVER,
                            Zones.parseInstant("2025-01-18T10:00:00Z")));
            store.shields()
                    .add(Shield.of(
                            BO,
                            r("2025-01-20..2025-01-26"),
                            ShieldReason.ANNUAL_LEAVE,
                            Zones.parseInstant("2025-01-04T10:00:00Z")));
            assertThat(store.overrides().byMember(ADA)).hasSize(1);
            assertThat(store.overrides().byMember(BO)).isEmpty();
            assertThat(store.shields().byMember(BO)).hasSize(1);
        }
    }

    @Nested
    class Crews {

        @Test
        void savesACrewAndItsMemberships() {
            store.crews().save(Crew.of(ALPHA, "Alpha production", "Grane B", "Europe/Oslo"));
            store.crews().addMembership(Membership.of(ALPHA, ADA, CivilDate.parse("2025-01-01")));
            assertThat(store.crews().byId(ALPHA).map(Crew::site)).hasValue("Grane B");
            assertThat(store.crews().memberships()).hasSize(1);
        }

        @Test
        void replacesAMembershipInPlace() {
            Membership entry = Membership.of(ALPHA, ADA, CivilDate.parse("2025-01-01"));
            store.crews().addMembership(entry);
            store.crews().replaceMembership(entry.endOn(CivilDate.parse("2025-06-30")));
            assertThat(store.crews().memberships().get(0).to()).isPresent();
        }

        @Test
        void refusesToReplaceAMembershipItDoesNotHold() {
            Membership entry = Membership.of(ALPHA, BO, CivilDate.parse("2025-01-01"));
            assertThatThrownBy(() -> store.crews().replaceMembership(entry)).isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class SharingAndPolicies {

        @Test
        void storesOneRowPerMember() {
            store.sharing().save(SharingPreference.of(ADA, Visibility.CREW));
            store.policies().save(ADA, NudgePolicy.builder().maxPerDay(1).build());
            assertThat(store.sharing().byMember(ADA).map(SharingPreference::fallback))
                    .hasValue(Visibility.CREW);
            assertThat(store.policies().byMember(ADA).map(NudgePolicy::maxPerDay))
                    .hasValue(1);
            assertThat(store.policies().byMember(BO)).isEmpty();
        }
    }

    @Nested
    class Reset {

        @Test
        void emptiesEverything() {
            store.members().save(Fixtures.aMember());
            store.habits().save(Fixtures.aHabit());
            store.checkIns().add(anEntry("chk_0000000000000001", "2025-01-10T07:00:00Z"));
            store.reset();
            assertThat(store.members().all()).isEmpty();
            assertThat(store.habits().byMember(ADA)).isEmpty();
            assertThat(store.checkIns().byMember(ADA)).isEmpty();
        }
    }

    @Nested
    class TheSuppliedClockAndIds {

        @Test
        void areTheOnesHandedIn() {
            assertThat(store.clock().now()).isEqualTo(Zones.parseInstant("2025-01-10T09:00:00Z"));
            assertThat(store.ids().next(IdKind.HABIT)).startsWith("hab_");
            assertThat(store.events()).isEqualTo(Optional.empty());
        }
    }
}
