package io.github.rkaushikdev.hitchline.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.cli.Context;
import io.github.rkaushikdev.hitchline.cli.Main;
import io.github.rkaushikdev.hitchline.cli.Recorder;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.domain.crew.Crew;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CliCrewTest {

    private static final Config CONFIG = Config.read(Map.of()).withStore(StoreKind.MEMORY);

    private MemoryStore store;
    private Recorder context;

    @BeforeEach
    void create() {
        store = MemoryStore.create(Clocks.fixedClock("2025-02-10T09:00:00Z"), IdSources.sequentialIds());
        context = Context.recordingContext(store, CONFIG);
    }

    private int run(String... args) {
        return Main.run(context, args);
    }

    private List<String> lines() {
        return Texts.split(context.text(), "\n");
    }

    private String firstCrew() {
        return store.crews().all().stream().findFirst().map(Crew::id).orElse("");
    }

    private String firstMember() {
        return store.members().all().stream().findFirst().map(CrewMember::id).orElse("");
    }

    @Nested
    class DemoSeed {

        @Test
        void fillsTheStoreAndReportsWhatItWrote() {
            assertThat(run("demo", "seed")).isEqualTo(Context.EXIT_OK);
            assertThat(context.text()).contains("members    5");
            assertThat(context.text()).contains("habits     20");
            assertThat(store.members().all()).hasSize(5);
        }

        @Test
        void takesALengthAndADiligence() {
            run("demo", "seed", "--days", "7", "--adherence", "1");
            assertThat(context.text()).contains("to         2025-01-12");
        }
    }

    @Nested
    class CrewCommands {

        @Test
        void startsACrewAndPrintsItsId() {
            int code = run("crew", "add", "--name", "Alpha production", "--site", "Grane B", "--zone", "Europe/Oslo");
            assertThat(code).isEqualTo(Context.EXIT_OK);
            assertThat(context.text()).contains("Alpha production at Grane B");
        }

        @Test
        void refusesAZoneItDoesNotKnow() {
            int code = run("crew", "add", "--name", "Alpha", "--site", "Grane B", "--zone", "North Sea/Alpha");
            assertThat(code).isGreaterThan(Context.EXIT_OK);
        }

        @Test
        void enrolsSomebodyAndListsThem() {
            run("demo", "seed", "--days", "7");
            String crewId = firstCrew();
            context.clear();
            run("crew", "members", crewId, "--on", "2025-01-10");
            List<String> lines = lines();
            assertThat(lines.get(0)).contains("name");
            assertThat(lines).hasSize(7);
        }

        @Test
        void countsWhoIsOnBoardEachDayAndFlagsTheGaps() {
            run("demo", "seed", "--days", "28");
            String crewId = firstCrew();
            context.clear();
            int code = run("crew", "members", crewId, "--window", "2025-01-19..2025-01-20", "--beds", "2");
            assertThat(code).isEqualTo(Context.EXIT_OK);
            List<String> lines = lines();
            assertThat(lines.get(0)).containsPattern("date +on board +travelling +leads +flag");
            assertThat(lines.get(2)).containsPattern("2025-01-19 +3 +0 +1 +1 over the 2 beds");
            assertThat(lines.get(3)).containsPattern("2025-01-20 +2 +0 +0 +no lead on board");
        }

        @Test
        void wantsEitherADayOrAWindow() {
            run("demo", "seed", "--days", "7");
            String crewId = firstCrew();
            assertThat(run("crew", "members", crewId)).isEqualTo(Context.EXIT_MISUSED);
            assertThat(run("crew", "members", crewId, "--on", "2025-01-10", "--window", "2025-01-10..2025-01-11"))
                    .isEqualTo(Context.EXIT_MISUSED);
            assertThat(context.errorText()).contains("give either --on or --window");
            assertThat(run("crew", "members", crewId, "--window", "2025-01-10..2025-01-11", "--beds", "lots"))
                    .isEqualTo(Context.EXIT_MISUSED);
        }

        @Test
        void reportsACrewItDoesNotHold() {
            int code = run("crew", "members", "crw_9999999999999999", "--on", "2025-01-10");
            assertThat(code).isEqualTo(Context.EXIT_OK);
            assertThat(lines()).hasSize(2);
        }

        @Test
        void comparesTheCrewAsOneMemberSeesIt() {
            run("demo", "seed", "--days", "14", "--adherence", "1");
            String crewId = firstCrew();
            String lead = firstMember();
            context.clear();
            int code = run("crew", "compare", crewId, lead, "--window", "2025-01-06..2025-01-19", "--on", "2025-01-10");
            assertThat(code).isEqualTo(Context.EXIT_OK);
            assertThat(context.text()).contains("hidden");
            assertThat(context.text()).contains("crew");
        }

        @Test
        void refusesAComparisonWithNoWindow() {
            run("demo", "seed", "--days", "7");
            String crewId = firstCrew();
            String lead = firstMember();
            context.clear();
            assertThat(run("crew", "compare", crewId, lead, "--on", "2025-01-10"))
                    .isEqualTo(Context.EXIT_FAILED);
            assertThat(context.errorText()).contains("--window");
            assertThat(context.text()).isEmpty();
        }

        @Test
        void reportsAnEnrolmentClash() {
            run("demo", "seed", "--days", "7");
            String crewId = firstCrew();
            String member = firstMember();
            context.clear();
            int code = run("crew", "enrol", crewId, member, "--from", "2025-06-01");
            assertThat(code).isEqualTo(Context.EXIT_FAILED);
            assertThat(context.errorText()).contains("conflict");
        }
    }
}
