package io.github.rkaushikdev.hitchline.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.cli.Context;
import io.github.rkaushikdev.hitchline.cli.Main;
import io.github.rkaushikdev.hitchline.cli.Recorder;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliExportTest {

    private static final Config CONFIG = Config.read(Map.of()).withStore(StoreKind.MEMORY);
    private static final String WINDOW = "2025-01-06..2025-01-19";

    @TempDir
    Path directory;

    private MemoryStore store;
    private Recorder context;
    private String member;

    @BeforeEach
    void seed() {
        store = MemoryStore.create(Clocks.fixedClock("2025-02-10T09:00:00Z"), IdSources.sequentialIds());
        context = Context.recordingContext(store, CONFIG);
        run("demo", "seed", "--days", "14", "--adherence", "1");
        member = store.members().all().stream().findFirst().map(CrewMember::id).orElse("");
        context.clear();
    }

    private int run(String... args) {
        return Main.run(context, args);
    }

    private List<String> csvLines() {
        return Texts.split(context.text(), "\r\n");
    }

    @Nested
    class ExportingCheckIns {

        @Test
        void writesACsvHeaderAndALineForEachRow() {
            assertThat(run("export", "checkins", member, "--window", WINDOW)).isEqualTo(Context.EXIT_OK);
            List<String> lines = csvLines();
            assertThat(lines.get(0)).isEqualTo("id,habit_id,member_id,at,rotation_day,role,zone,reading,source,note");
            assertThat(lines.size()).isGreaterThan(2);
        }

        @Test
        void writesToAFileWhenAsked() throws IOException {
            Path file = directory.resolve("checkins.csv");
            run("export", "checkins", member, "--window", WINDOW, "--out", file.toString());
            assertThat(context.text()).contains("lines to");
            assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("rotation_day");
        }

        @Test
        void reportsAMemberItDoesNotHold() {
            int code = run("export", "checkins", "mem_9999999999999999", "--window", WINDOW);
            assertThat(code).isEqualTo(Context.EXIT_FAILED);
        }
    }

    @Nested
    class ExportingARoster {

        @Test
        void writesALineForEachDay() {
            run("export", "roster", member, "--window", "2025-01-06..2025-01-08");
            List<String> lines = csvLines();
            assertThat(lines.get(0)).isEqualTo("date,role,source,zone");
            assertThat(lines).hasSize(4);
        }
    }

    @Nested
    class ExportingAdherence {

        @Test
        void writesALineForEachObligation() {
            run("export", "adherence", member, "--window", WINDOW);
            List<String> lines = csvLines();
            assertThat(lines.get(0)).contains("habit_id,scope,window,due_on");
            assertThat(lines.size()).isGreaterThan(2);
        }
    }

    @Nested
    class ExportingACalendar {

        @Test
        void writesACalendarWithEventsInIt() {
            run("export", "calendar", member, "--window", WINDOW);
            String body = context.text();
            assertThat(body).startsWith("BEGIN:VCALENDAR");
            assertThat(body).contains("BEGIN:VEVENT");
            assertThat(body).contains("X-WR-CALNAME:Ada Vasquez hitchline");
        }

        @Test
        void writesItToAFileWhenAsked() throws IOException {
            Path file = directory.resolve("hitch.ics");
            run("export", "calendar", member, "--window", WINDOW, "--out", file.toString());
            assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("END:VCALENDAR");
        }
    }
}
