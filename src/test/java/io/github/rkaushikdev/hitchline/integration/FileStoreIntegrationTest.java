package io.github.rkaushikdev.hitchline.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.app.AdherenceService;
import io.github.rkaushikdev.hitchline.app.CheckInsService;
import io.github.rkaushikdev.hitchline.app.CheckInsService.RecordCheckInCommand;
import io.github.rkaushikdev.hitchline.app.CrewService;
import io.github.rkaushikdev.hitchline.app.HabitsService;
import io.github.rkaushikdev.hitchline.app.HabitsService.CreateHabitCommand;
import io.github.rkaushikdev.hitchline.app.NudgesService;
import io.github.rkaushikdev.hitchline.app.RosterService;
import io.github.rkaushikdev.hitchline.app.RosterService.HoldCommand;
import io.github.rkaushikdev.hitchline.app.RosterService.ShieldCommand;
import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.adherence.PeriodSummary;
import io.github.rkaushikdev.hitchline.domain.adherence.ShieldReason;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.crew.Crew;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.crew.Membership;
import io.github.rkaushikdev.hitchline.domain.crew.Visibility;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.nudge.NudgePolicy;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.OverrideReason;
import io.github.rkaushikdev.hitchline.domain.rotation.RosterOverride;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.FileStore;
import io.github.rkaushikdev.hitchline.infra.store.Locks;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileStoreIntegrationTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");

    @TempDir
    Path directory;

    private Path path;
    private FileStore store;

    private FileStore openStore() {
        return FileStore.open(path, Clocks.fixedClock("2025-01-10T09:00:00Z"), IdSources.sequentialIds(), true);
    }

    @BeforeEach
    void open() {
        path = directory.resolve("hitchline.jsonl");
        store = openStore();
    }

    @AfterEach
    void close() {
        store.close();
    }

    private FileStore reopen() {
        store.close();
        store = openStore();
        return store;
    }

    private Habit createHabit(String name, String cadence) {
        return new HabitsService(store)
                .create(CreateHabitCommand.of(ADA, name, cadence).withStartsOn("2025-01-06"));
    }

    private Crew alpha() {
        return new CrewService(store).create("Alpha production", "Grane B", "Europe/Oslo");
    }

    @Nested
    class WritingAndReloading {

        @Test
        void survivesACloseAndReopen() {
            store.members().save(Fixtures.aMember());
            Habit created = createHabit("Twenty minutes on the treadmill", "1/onshift-day");

            FileStore reopened = reopen();
            assertThat(reopened.members().byId(ADA).map(CrewMember::displayName))
                    .hasValue("Ada Vasquez");
            assertThat(reopened.habits().byId(created.id()).map(Habit::name))
                    .hasValue("Twenty minutes on the treadmill");
        }

        @Test
        void writesOneLinePerChange() throws IOException {
            store.members().save(Fixtures.aMember());
            store.members().save(Fixtures.aMember(o -> o.displayName("Ada Nkemdirim")));
            String text = Files.readString(path, StandardCharsets.UTF_8);
            assertThat(Texts.split(Texts.trim(text), "\n")).hasSize(2);
        }

        @Test
        void keepsTheLastWriteForARecord() {
            store.members().save(Fixtures.aMember());
            store.members().save(Fixtures.aMember(o -> o.displayName("Ada Nkemdirim")));
            FileStore reopened = reopen();
            assertThat(reopened.members().byId(ADA).map(CrewMember::displayName))
                    .hasValue("Ada Nkemdirim");
            assertThat(reopened.members().all()).hasSize(1);
        }

        @Test
        void startsEmptyOnAFreshPath() {
            assertThat(store.members().all()).isEmpty();
            assertThat(store.crews().all()).isEmpty();
        }
    }

    @Nested
    class AFullRunThroughTheServices {

        @BeforeEach
        void enrolAda() {
            store.members().save(Fixtures.aMember());
        }

        @Test
        void recordsCheckInsAndReportsOnThemAfterAReload() {
            Habit created = createHabit("Twenty minutes on the treadmill", "1/onshift-day");
            CheckInsService checkIns = new CheckInsService(store);
            checkIns.record(RecordCheckInCommand.of(created.id()).withAt("2025-01-06T07:00:00Z"));
            checkIns.record(RecordCheckInCommand.of(created.id()).withAt("2025-01-07T07:00:00Z"));

            FileStore reopened = reopen();
            PeriodSummary summary = new AdherenceService(reopened)
                    .report(ADA, "2025-01-06..2025-01-09")
                    .summary();
            assertThat(summary.owed()).isEqualTo(4);
            assertThat(summary.met()).isEqualTo(2);
        }

        @Test
        void keepsOverridesAndShieldsAcrossAReload() {
            RosterService rosters = new RosterService(store);
            rosters.hold(new HoldCommand(
                    ADA,
                    "2025-01-20..2025-01-22",
                    DayRole.ONSHIFT,
                    OverrideReason.HELD_OVER,
                    Optional.of("relief weathered off")));
            rosters.shieldDays(new ShieldCommand(
                    ADA, "2025-02-03..2025-02-05", ShieldReason.ANNUAL_LEAVE, Optional.empty(), Optional.empty()));

            FileStore reopened = reopen();
            List<RosterOverride> overrides = reopened.overrides().byMember(ADA);
            assertThat(overrides).hasSize(1);
            assertThat(overrides.get(0).note()).isEqualTo("relief weathered off");
            assertThat(reopened.shields().byMember(ADA)).hasSize(1);
        }

        @Test
        void keepsAVoidedCheckInAsVoided() {
            Habit created = createHabit("Row", "1/day");
            CheckInsService checkIns = new CheckInsService(store);
            CheckIn entry = checkIns.record(
                            RecordCheckInCommand.of(created.id()).withAt("2025-01-08T07:00:00Z"))
                    .entry();
            checkIns.voidEntry(entry.id());

            FileStore reopened = reopen();
            assertThat(reopened.checkIns().byId(entry.id()).orElseThrow().voidedAt())
                    .isPresent();
            assertThat(new CheckInsService(reopened).listFor(ADA)).isEmpty();
        }

        @Test
        void keepsCrewsMembershipsAndSharingAcrossAReload() {
            CrewService crews = new CrewService(store);
            Crew crew = alpha();
            crews.enrol(crew.id(), ADA, "2025-01-01", Optional.empty());
            crews.setVisibility(ADA, Visibility.CREW);

            CrewService back = new CrewService(reopen());
            assertThat(back.crewFor(ADA, "2025-02-01").map(Crew::name)).hasValue("Alpha production");
            assertThat(back.visibilityFor(ADA).fallback()).isEqualTo(Visibility.CREW);
        }

        @Test
        void keepsANudgePolicyAcrossAReload() {
            new NudgesService(store)
                    .setPolicy(ADA, NudgePolicy.builder().maxPerDay(1).quietHours("23:00-07:00"));
            NudgePolicy policy = new NudgesService(reopen()).policyFor(ADA);
            assertThat(policy.maxPerDay()).isEqualTo(1);
            assertThat(policy.quietHours().to().hour()).isEqualTo(7);
        }

        @Test
        void endsAMembershipInPlaceRatherThanAddingASecondRow() {
            CrewService crews = new CrewService(store);
            Crew crew = alpha();
            crews.enrol(crew.id(), ADA, "2025-01-01", Optional.empty());
            crews.release(crew.id(), ADA, "2025-06-30");

            List<Membership> rows = reopen().crews().memberships();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).to().map(CivilDate::month)).hasValue(6);
        }
    }

    @Nested
    class ReloadOnDemand {

        @Test
        void picksUpWhatAMaintenanceToolAppended() {
            store.members().save(Fixtures.aMember());
            try (FileStore second =
                    FileStore.open(path, Clocks.fixedClock("2025-01-10T10:00:00Z"), IdSources.randomIds(), false)) {
                second.members()
                        .save(Fixtures.aMember(o -> o.id("mem_FEDCBA9876543210").displayName("Bo Lindqvist")));
            }

            assertThat(store.members().all()).hasSize(1);
            store.reload();
            assertThat(store.members().all()).hasSize(2);
        }
    }

    @Nested
    class TheWriterLock {

        @Test
        void isHeldWhileTheStoreIsOpen() {
            assertThat(Locks.readLock(path)).isPresent();
        }

        @Test
        void stopsASecondWriterOpeningTheSameJournal() {
            assertThatThrownBy(() -> FileStore.open(path)).isInstanceOf(ConflictException.class);
        }

        @Test
        void isReleasedOnClose() {
            store.close();
            assertThat(Locks.readLock(path)).isEmpty();
            store = FileStore.open(path, Clocks.fixedClock("2025-01-10T09:00:00Z"), IdSources.randomIds(), true);
        }

        @Test
        void letsAReaderInWhenItAsksNotToLock() {
            FileStore reader = FileStore.open(path, Clocks.systemClock(), IdSources.randomIds(), false);
            assertThat(reader.members().all()).isEmpty();
            reader.close();
            assertThat(Locks.readLock(path)).isPresent();
        }
    }
}
