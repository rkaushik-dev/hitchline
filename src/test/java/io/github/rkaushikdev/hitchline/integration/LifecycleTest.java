package io.github.rkaushikdev.hitchline.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.app.AdherenceService;
import io.github.rkaushikdev.hitchline.app.AdherenceService.AdherenceReport;
import io.github.rkaushikdev.hitchline.app.AuditEntry;
import io.github.rkaushikdev.hitchline.app.AuditLog;
import io.github.rkaushikdev.hitchline.app.CheckInsService;
import io.github.rkaushikdev.hitchline.app.CheckInsService.RecordCheckInCommand;
import io.github.rkaushikdev.hitchline.app.CompareService;
import io.github.rkaushikdev.hitchline.app.CompareService.CrewComparison;
import io.github.rkaushikdev.hitchline.app.CompareService.CrewLine;
import io.github.rkaushikdev.hitchline.app.CrewService;
import io.github.rkaushikdev.hitchline.app.Digest;
import io.github.rkaushikdev.hitchline.app.EventBus;
import io.github.rkaushikdev.hitchline.app.EventName;
import io.github.rkaushikdev.hitchline.app.HabitsService;
import io.github.rkaushikdev.hitchline.app.HabitsService.CreateHabitCommand;
import io.github.rkaushikdev.hitchline.app.ImportService;
import io.github.rkaushikdev.hitchline.app.ImportService.ImportOptions;
import io.github.rkaushikdev.hitchline.app.ImportService.ImportReport;
import io.github.rkaushikdev.hitchline.app.LoadedMember;
import io.github.rkaushikdev.hitchline.app.MemberLoader;
import io.github.rkaushikdev.hitchline.app.NudgesService;
import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.app.RosterService;
import io.github.rkaushikdev.hitchline.app.RosterService.HoldCommand;
import io.github.rkaushikdev.hitchline.app.Seed;
import io.github.rkaushikdev.hitchline.app.Seed.SeedOptions;
import io.github.rkaushikdev.hitchline.app.Seed.SeedReport;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.adherence.StreakSummary;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitStatus;
import io.github.rkaushikdev.hitchline.domain.nudge.PlannedNudge;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.OverrideReason;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.export.CsvExport;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.MutableClock;
import io.github.rkaushikdev.hitchline.infra.store.FileStore;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One hitch, start to finish, against the store that actually ships. Every other integration test
 * covers one seam; this one is the whole road, and it is the test that would have caught the two
 * bugs where a service and its repository disagreed about what had been written.
 */
class LifecycleTest {

    private static final String ADA = Ids.crewMemberId("mem_0000000000000001");

    @TempDir
    Path directory;

    private Path path;
    private FileStore store;
    private Ports ports;
    private MutableClock clock;
    private AuditLog log;

    @BeforeEach
    void open() {
        path = directory.resolve("hitchline.jsonl");
        clock = Clocks.fixedClock("2025-01-20T09:00:00Z");
        store = FileStore.open(path, clock, IdSources.sequentialIds(), true);
        EventBus bus = EventBus.create();
        log = AuditLog.create();
        AuditLog.auditEverything(bus, log);
        ports = store.withEvents(bus);
    }

    @AfterEach
    void close() {
        store.close();
    }

    private SeedReport seedDemo(int days, double adherence) {
        return Seed.seedDemo(
                ports, SeedOptions.DEFAULT.withFrom("2025-01-06").withDays(days).withAdherence(adherence));
    }

    @Nested
    class AHitchFromStartToFinish {

        @Test
        void carriesOneMemberFromEnrolmentToTheEndOfHitchNote() {
            // The crewing office loads the rota and the standard habits.
            SeedReport seeded = seedDemo(14, 1);
            assertThat(seeded.members()).isEqualTo(5);

            // The medic adds one more habit part way through the hitch.
            HabitsService habits = new HabitsService(ports);
            Habit extra = habits.applyTemplate(ADA, "kit-check", Optional.of("2025-01-13"), Optional.empty());
            assertThat(extra.name()).isEqualTo("Check the survival suit");

            // The person does it, late enough that a supervisor has to record it.
            CheckInsService checkIns = new CheckInsService(ports);
            checkIns.record(RecordCheckInCommand.of(extra.id())
                    .withAt("2025-01-14T08:00:00Z")
                    .withSource(CheckInSource.SUPERVISOR)
                    .withNote("suit checked with the medic"));

            // Weather holds the relief crew ashore and the hitch runs three days over.
            new RosterService(ports)
                    .hold(new HoldCommand(
                            ADA,
                            "2025-01-20..2025-01-22",
                            DayRole.ONSHIFT,
                            OverrideReason.WEATHER,
                            Optional.of("relief weathered off")));

            // The report reads the whole thing, held over days and all.
            clock.set(Zones.parseInstant("2025-01-23T09:00:00Z"));
            AdherenceReport report = new AdherenceService(ports).report(ADA, "2025-01-06..2025-01-22");
            assertThat(report.summary().owed()).isGreaterThan(14);
            assertThat(report.byRole().stream().anyMatch(split -> split.role() == DayRole.ONSHIFT))
                    .isTrue();

            // And the note that goes out at the end of it names the habits by name.
            LoadedMember loaded = new MemberLoader(ports).load(ADA);
            Map<String, StreakSummary> streaks = new LinkedHashMap<>();
            for (Habit habit : loaded.habits()) {
                streaks.put(
                        habit.id(),
                        StreakSummary.compute(
                                new MemberLoader(ports).scoreOne(loaded, habit, report.window()),
                                loaded.shields(),
                                ADA));
            }
            String note = new Digest(
                            loaded.roster().member(), report.summary(), loaded.habits(), report.results(), streaks)
                    .text();
            assertThat(note).contains("Ada Vasquez");
            assertThat(note).contains("Check the survival suit");
        }

        @Test
        void survivesARestartInTheMiddleOfTheHitch() {
            seedDemo(14, 1);
            AdherenceReport before = new AdherenceService(ports).report(ADA, "2025-01-06..2025-01-19");

            store.close();
            store = FileStore.open(path, clock, IdSources.sequentialIds(), true);
            AdherenceReport after = new AdherenceService(store).report(ADA, "2025-01-06..2025-01-19");

            assertThat(after.summary()).isEqualTo(before.summary());
            assertThat(after.results()).hasSize(before.results().size());
        }

        @Test
        void letsALeadReadTheCrewWithoutSeeingWhatNobodyShared() {
            SeedReport seeded = seedDemo(14, 1);
            CrewComparison comparison = new CompareService(ports)
                    .crew(ADA, Ids.crewId(seeded.crewId()), "2025-01-06..2025-01-19", "2025-01-10");
            assertThat(comparison.lines()).hasSize(5);
            Optional<CrewLine> mine = comparison.lines().stream()
                    .filter(line -> line.memberId().equals(ADA))
                    .findFirst();
            assertThat(mine.map(CrewLine::hidden)).hasValue(0);
            assertThat(comparison.lines().stream().filter(line -> line.hidden() > 0))
                    .hasSize(4);
        }

        @Test
        void importsAFortnightOfPaperRecordsAndReportsOnThem() {
            seedDemo(1, 0);
            String habit = ports.habits().byMember(ADA).stream()
                    .findFirst()
                    .map(Habit::id)
                    .orElse("");
            String rows = String.join(
                    "\n",
                    "habit_id,at,reading,note,idempotency_key",
                    habit + ",2025-01-07T07:00:00Z,22,,paper-01",
                    habit + ",2025-01-08T07:00:00Z,24,,paper-02",
                    habit + ",not a time,20,,paper-03");

            ImportOptions asSupervisor = new ImportOptions(false, CheckInSource.SUPERVISOR);
            ImportReport report = new ImportService(ports).checkIns(rows, asSupervisor);
            assertThat(report.accepted()).isEqualTo(2);
            assertThat(report.failures()).hasSize(1);

            ImportReport again = new ImportService(ports).checkIns(rows, asSupervisor);
            assertThat(again.duplicates()).isEqualTo(2);
        }

        @Test
        void plansANudgeOnlyForWhatIsStillOpen() {
            seedDemo(14, 0);
            clock.set(Zones.parseInstant("2025-01-15T06:00:00Z"));
            List<PlannedNudge> plan = new NudgesService(ports).plan(ADA, "2025-01-15..2025-01-19");
            assertThat(plan).isNotEmpty();
            for (PlannedNudge nudge : plan) {
                assertThat(nudge.at()).isAfterOrEqualTo(clock.now());
            }
        }

        @Test
        void exportsWhatASpreadsheetAsksFor() {
            seedDemo(14, 1);
            List<ResolvedCheckIn> resolved = new CheckInsService(ports).listFor(ADA);
            String csv = CsvExport.checkInsToCsv(resolved);
            assertThat(Texts.split(csv, "\r\n").get(0)).contains("rotation_day");
            assertThat(Texts.split(csv, "\r\n")).hasSizeGreaterThan(2);

            AdherenceReport report = new AdherenceService(ports).report(ADA, "2025-01-06..2025-01-19");
            assertThat(Texts.split(CsvExport.resultsToCsv(report.results()), "\r\n")
                            .get(0))
                    .contains("due_on");
        }

        @Test
        void writesAnAuditLineForEverythingAnybodyDid() {
            HabitsService habits = new HabitsService(ports);
            seedDemo(1, 0);
            Habit created = habits.create(
                    CreateHabitCommand.of(ADA, "Row the ergometer", "1/day").withStartsOn("2025-01-06"));
            habits.changeCadence(created.id(), "3/hitch");
            habits.moveTo(created.id(), HabitStatus.ARCHIVED);

            List<String> names = log.forMember(ADA).stream()
                    .map(AuditEntry::name)
                    .map(EventName::wire)
                    .toList();
            assertThat(names).containsExactly("habit.created", "habit.changed", "habit.archived");
        }

        @Test
        void keepsTheCrewReadableAYearLater() {
            SeedReport seeded = seedDemo(14, 1);
            List<CrewMember> members = new CrewService(ports).membersOfCrew(Ids.crewId(seeded.crewId()), "2025-12-31");
            assertThat(members).hasSize(5);
            assertThat(seeded.to()).hasToString("2025-01-19");
        }
    }
}
