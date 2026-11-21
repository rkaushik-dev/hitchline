package io.github.rkaushikdev.hitchline.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.app.Seed.SeedOptions;
import io.github.rkaushikdev.hitchline.app.Seed.SeedReport;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.crew.CrewRole;
import io.github.rkaushikdev.hitchline.domain.crew.Membership;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SeedTest {

    private MemoryStore store;

    @BeforeEach
    void create() {
        store = MemoryStore.create(Clocks.fixedClock("2025-02-10T09:00:00Z"), IdSources.sequentialIds());
    }

    private MemoryStore another() {
        return MemoryStore.create(store.clock(), IdSources.sequentialIds());
    }

    @Nested
    class SeedingADemo {

        @Test
        void buildsACrewWithEverybodyInIt() {
            SeedReport report = Seed.seedDemo(store);
            assertThat(report.members()).isEqualTo(5);
            assertThat(store.members().all()).hasSize(5);
            assertThat(store.crews().memberships()).hasSize(5);
        }

        @Test
        void givesEverybodyTheSameSetOfHabits() {
            SeedReport report = Seed.seedDemo(store);
            assertThat(report.habits()).isEqualTo(20);
            assertThat(store.habits().byMember(Ids.crewMemberId("mem_0000000000000001")))
                    .hasSize(4);
        }

        @Test
        void recordsAFortnightOfHistoryByDefault() {
            SeedReport report = Seed.seedDemo(store);
            assertThat(report.from()).hasToString("2025-01-06");
            assertThat(report.to()).hasToString("2025-02-02");
            assertThat(report.checkIns()).isPositive();
        }

        @Test
        void isTheSameTwiceForTheSameSeed() {
            SeedReport first = Seed.seedDemo(store);
            SeedReport second = Seed.seedDemo(another());
            assertThat(second.checkIns()).isEqualTo(first.checkIns());
        }

        @Test
        void doesMoreWhenToldToBeMoreDiligent() {
            SeedReport lazy = Seed.seedDemo(store, SeedOptions.DEFAULT.withAdherence(0.2));
            SeedReport keen = Seed.seedDemo(another(), SeedOptions.DEFAULT.withAdherence(1));
            assertThat(keen.checkIns()).isGreaterThan(lazy.checkIns());
        }

        @Test
        void takesAStartDateAndALength() {
            SeedReport report = Seed.seedDemo(
                    store, SeedOptions.DEFAULT.withFrom("2025-03-03").withDays(7));
            assertThat(report.from()).hasToString("2025-03-03");
            assertThat(report.to()).hasToString("2025-03-09");
        }

        @Test
        void putsTheCrewLeadFirst() {
            SeedReport report = Seed.seedDemo(store);
            List<Membership> rows = store.crews().memberships();
            assertThat(rows.get(0).role()).isEqualTo(CrewRole.LEAD);
            assertThat(report.crewId()).startsWith("crw_");
        }

        @Test
        void onlyRecordsShiftDayHabitsOnShiftDays() {
            Seed.seedDemo(store, SeedOptions.DEFAULT.withAdherence(1));
            AdherenceService.AdherenceReport report = new AdherenceService(store)
                    .report(Ids.crewMemberId("mem_0000000000000001"), "2025-01-06..2025-02-02");
            assertThat(report.summary().missed()).isZero();
        }

        @Test
        void leavesTheCrewReadableThroughTheCrewService() {
            SeedReport report = Seed.seedDemo(store);
            assertThat(new CrewService(store).membersOfCrew(Ids.crewId(report.crewId()), "2025-01-10"))
                    .hasSize(5);
        }
    }
}
