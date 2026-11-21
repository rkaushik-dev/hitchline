package io.github.rkaushikdev.hitchline;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.app.AdherenceService;
import io.github.rkaushikdev.hitchline.app.AdherenceService.AdherenceReport;
import io.github.rkaushikdev.hitchline.app.CheckInsService;
import io.github.rkaushikdev.hitchline.app.CompareService;
import io.github.rkaushikdev.hitchline.app.CrewService;
import io.github.rkaushikdev.hitchline.app.HabitsService;
import io.github.rkaushikdev.hitchline.app.ImportService;
import io.github.rkaushikdev.hitchline.app.NudgesService;
import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.app.RosterService;
import io.github.rkaushikdev.hitchline.app.SearchService;
import io.github.rkaushikdev.hitchline.app.Seed;
import io.github.rkaushikdev.hitchline.app.Seed.SeedOptions;
import io.github.rkaushikdev.hitchline.app.Seed.SeedReport;
import io.github.rkaushikdev.hitchline.app.SyncService;
import io.github.rkaushikdev.hitchline.app.SyncService.SyncReport;
import io.github.rkaushikdev.hitchline.cli.Main;
import io.github.rkaushikdev.hitchline.domain.DomainException;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.adherence.FrozenStreak;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.adherence.Quota;
import io.github.rkaushikdev.hitchline.domain.adherence.StreakSummary;
import io.github.rkaushikdev.hitchline.domain.adherence.SummaryRequest;
import io.github.rkaushikdev.hitchline.domain.crew.Crew;
import io.github.rkaushikdev.hitchline.domain.crew.MemberPreferences;
import io.github.rkaushikdev.hitchline.domain.crew.Membership;
import io.github.rkaushikdev.hitchline.domain.crew.SharingPreference;
import io.github.rkaushikdev.hitchline.domain.habit.Cadence;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitTemplate;
import io.github.rkaushikdev.hitchline.domain.nudge.Dispatch;
import io.github.rkaushikdev.hitchline.domain.nudge.NudgePlanner;
import io.github.rkaushikdev.hitchline.domain.nudge.NudgePolicy;
import io.github.rkaushikdev.hitchline.domain.rotation.RoleCalendar;
import io.github.rkaushikdev.hitchline.domain.rotation.Roster;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationAssignment;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationPattern;
import io.github.rkaushikdev.hitchline.domain.rotation.Zoning;
import io.github.rkaushikdev.hitchline.domain.schedule.Expansion;
import io.github.rkaushikdev.hitchline.export.CsvExport;
import io.github.rkaushikdev.hitchline.export.IcsExport;
import io.github.rkaushikdev.hitchline.http.Api;
import io.github.rkaushikdev.hitchline.http.ApiDocs;
import io.github.rkaushikdev.hitchline.http.Catalogue;
import io.github.rkaushikdev.hitchline.http.Manifest;
import io.github.rkaushikdev.hitchline.imports.CsvReader;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.FileStore;
import io.github.rkaushikdev.hitchline.infra.store.Journal;
import io.github.rkaushikdev.hitchline.infra.store.LockHolder;
import io.github.rkaushikdev.hitchline.infra.store.Locks;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The surface other people build against: the public types an embedder names. A name leaving this
 * list is a breaking change, so the test spells out what is on it rather than counting.
 */
class PublicSurfaceTest {

    private static final Set<Class<?>> STORE_INTERNALS = Set.of(Journal.class, LockHolder.class);

    /** Asserts {@code type} is public and offers each of {@code methods} as a public method. */
    private static void exports(Class<?> type, String... methods) {
        assertThat(Modifier.isPublic(type.getModifiers())).as(type.getName()).isTrue();
        for (String name : methods) {
            assertThat(Arrays.stream(type.getMethods()).map(Method::getName))
                    .as(type.getSimpleName() + "." + name)
                    .contains(name);
        }
    }

    /** True when any public method of {@code type} hands out or takes a piece of the store's insides. */
    private static boolean leaksStoreInternals(Class<?> type) {
        return Arrays.stream(type.getMethods())
                .anyMatch(method -> STORE_INTERNALS.contains(method.getReturnType())
                        || Arrays.stream(method.getParameterTypes()).anyMatch(STORE_INTERNALS::contains));
    }

    @Nested
    class ThePublicSurface {

        @Test
        void exportsTheRotationBuildingBlocks() {
            exports(RotationPattern.class, "parse");
            exports(RotationAssignment.class, "of");
            exports(Roster.class, "of");
            exports(Zoning.class, "rotationDayAt");
        }

        @Test
        void exportsHabitsCadencesAndExpansion() {
            exports(Habit.class, "builder");
            exports(Cadence.class, "parse");
            exports(Expansion.class, "of", "expand", "expandAll");
            exports(HabitTemplate.class, "find");
        }

        @Test
        void exportsScoringStreaksAndSummaries() {
            exports(ObligationResult.class, "evaluateAll");
            exports(StreakSummary.class, "compute");
            exports(FrozenStreak.class, "compute");
            exports(SummaryRequest.class, "summarise");
            exports(Quota.class, "read");
        }

        @Test
        void exportsTheServices() throws NoSuchMethodException {
            for (Class<?> service : List.of(
                    HabitsService.class,
                    CheckInsService.class,
                    RosterService.class,
                    AdherenceService.class,
                    NudgesService.class,
                    CrewService.class,
                    CompareService.class,
                    SearchService.class,
                    ImportService.class)) {
                exports(service);
                assertThat(Modifier.isPublic(service.getConstructor(Ports.class).getModifiers()))
                        .isTrue();
            }
        }

        @Test
        void exportsTheStoresAndTheTwoSurfaces() {
            exports(MemoryStore.class, "create");
            exports(FileStore.class, "open");
            exports(Api.class, "build");
            exports(Main.class, "buildProgram");
        }

        @Test
        void exportsTheErrorsACallerHasToHandle() {
            exports(ValidationException.class);
            exports(NotFoundException.class);
            exports(DomainException.class, "isDomainException");
            assertThat(DomainException.class).isAssignableFrom(ValidationException.class);
            assertThat(DomainException.class).isAssignableFrom(NotFoundException.class);
        }

        @Test
        void namesTheApiVersionItWasBuiltAt() {
            assertThat(Api.VERSION).matches("\\d+\\.\\d+\\.\\d+");
        }

        @Test
        void worksEndToEndThroughTheSurfaceAlone() {
            MemoryStore store =
                    MemoryStore.create(Clocks.fixedClock("2025-07-24T09:00:00Z"), IdSources.sequentialIds());
            SeedReport seeded = Seed.seedDemo(
                    store,
                    SeedOptions.DEFAULT.withFrom("2025-01-06").withDays(14).withAdherence(1));
            AdherenceReport report = new AdherenceService(store)
                    .report(Ids.crewMemberId("mem_0000000000000001"), "2025-01-06..2025-01-19");
            assertThat(seeded.members()).isEqualTo(5);
            assertThat(report.summary().owed()).isGreaterThan(0);
            assertThat(report.summary().missed()).isZero();
        }
    }

    @Nested
    class TheWiderSurface {

        @Test
        void exportsTheCrewAndSharingRules() {
            exports(Crew.class, "of");
            exports(Membership.class, "of");
            exports(SharingPreference.class, "canRead");
            exports(MemberPreferences.class, "builder");
        }

        @Test
        void exportsTheNudgePolicyThePlanAndTheDispatchRecord() {
            exports(NudgePolicy.class, "builder");
            exports(NudgePlanner.class, "plan");
            exports(Dispatch.class, "plan", "shouldRetry");
        }

        @Test
        void exportsTheRotationCalendar() {
            exports(RoleCalendar.class, "build", "blocks");
        }

        @Test
        void exportsTheImportAndExportHelpers() {
            exports(CsvExport.class, "checkInsToCsv");
            exports(IcsExport.class, "obligationsToCalendar");
            exports(CsvReader.class, "readTable");
        }

        @Test
        void exportsTheManifestAndTheErrorCatalogue() {
            assertThat(Manifest.ROUTES).isNotEmpty();
            assertThat(Catalogue.CATALOGUE).isNotEmpty();
            exports(Manifest.class, "openApiDocument");
            exports(ApiDocs.class, "apiMarkdown");
        }

        @Test
        void exportsTheSyncPathAHandsetUses() {
            exports(SyncService.class, "push");
            exports(SyncReport.class, "settledIds");
        }

        @Test
        void doesNotLeakTheInternalsOfTheStore() {
            assertThat(leaksStoreInternals(FileStore.class)).isFalse();
            assertThat(leaksStoreInternals(MemoryStore.class)).isFalse();
            assertThat(leaksStoreInternals(Ports.class)).isFalse();
            // The check itself can fail: the journal and the lock hand out exactly these types.
            assertThat(leaksStoreInternals(Journal.class)).isTrue();
            assertThat(leaksStoreInternals(Locks.class)).isTrue();
        }
    }
}
