package io.github.rkaushikdev.hitchline.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.hitchline.cli.Context;
import io.github.rkaushikdev.hitchline.cli.Main;
import io.github.rkaushikdev.hitchline.cli.Recorder;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.FileStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import io.github.rkaushikdev.hitchline.util.Json;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliStoreTest {

    @TempDir
    Path directory;

    private Path path;
    private FileStore store;
    private Recorder context;

    @BeforeEach
    void open() {
        path = directory.resolve("hitchline.jsonl");
        store = FileStore.open(path, Clocks.fixedClock("2025-03-14T09:00:00Z"), IdSources.sequentialIds(), true);
        context = Context.recordingContext(
                store, Config.read(Map.of()).withStore(StoreKind.FILE).withStorePath(path.toString()));
    }

    @AfterEach
    void close() {
        store.close();
    }

    private int run(String... args) {
        return Main.run(context, args);
    }

    private void seed() {
        store.members().save(Fixtures.aMember());
        store.members().save(Fixtures.aMember(o -> o.displayName("Ada Nkemdirim")));
        store.flush();
        context.clear();
    }

    private String takeBackup() {
        Path file = directory.resolve("backup.json");
        run("store", "backup", file.toString());
        return file.toString();
    }

    @Nested
    class StoreStatus {

        @Test
        void countsTheLiveAndTheDeadEntries() {
            seed();
            assertThat(run("store", "status")).isEqualTo(Context.EXIT_OK);
            String text = context.text();
            assertThat(text).contains("entries     2");
            assertThat(text).contains("live        1");
            assertThat(text).contains("dead        1");
        }

        @Test
        void saysWhetherCompactingIsWorthIt() {
            seed();
            run("store", "status");
            assertThat(context.text()).contains("worth doing");
        }

        @Test
        void readsAnEmptyJournal() {
            run("store", "status");
            assertThat(context.text()).contains("entries     0");
        }
    }

    @Nested
    class StoreCompact {

        @Test
        void rewritesTheFileAndSaysWhatItDropped() throws IOException {
            seed();
            assertThat(run("store", "compact")).isEqualTo(Context.EXIT_OK);
            assertThat(context.text()).contains("compacted 2 entries down to 1");
            String text = Files.readString(path, StandardCharsets.UTF_8);
            assertThat(Texts.split(Texts.trim(text), "\n")).hasSize(1);
        }

        @Test
        void leavesATidyJournalAlone() {
            store.members().save(Fixtures.aMember());
            store.flush();
            context.clear();
            run("store", "compact");
            assertThat(context.text()).contains("down to 1");
        }
    }

    @Nested
    class StoreBackupAndRestore {

        @Test
        void writesABackupAndDescribesIt() throws IOException {
            seed();
            Path file = directory.resolve("backup.json");
            assertThat(run("store", "backup", file.toString())).isEqualTo(Context.EXIT_OK);
            assertThat(context.text()).contains("member 1");
            assertThat(Json.parse(Files.readString(file, StandardCharsets.UTF_8))
                            .get("version")
                            .intValue())
                    .isEqualTo(1);
        }

        @Test
        void restoresIntoAnEmptyJournal() {
            seed();
            String file = takeBackup();
            String other = directory.resolve("other.jsonl").toString();
            context.clear();
            assertThat(run("store", "restore", file, "--path", other)).isEqualTo(Context.EXIT_OK);
            assertThat(context.text()).contains("restored 1 entry");
        }

        @Test
        void refusesToWriteOverAJournalThatHoldsSomething() {
            seed();
            String file = takeBackup();
            context.clear();
            assertThat(run("store", "restore", file)).isEqualTo(Context.EXIT_FAILED);
            assertThat(context.errorText()).contains("conflict");
        }

        @Test
        void writesOverItWhenForced() {
            seed();
            String file = takeBackup();
            context.clear();
            assertThat(run("store", "restore", file, "--force")).isEqualTo(Context.EXIT_OK);
        }

        @Test
        void reportsABackupFileItCannotRead() {
            int code = run("store", "restore", directory.resolve("nothing.json").toString());
            assertThat(code).isGreaterThan(Context.EXIT_OK);
        }
    }
}
