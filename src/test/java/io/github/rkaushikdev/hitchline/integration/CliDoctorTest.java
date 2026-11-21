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
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Fixtures;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliDoctorTest {

    @TempDir
    Path directory;

    private Path path;
    private FileStore store;
    private Recorder context;

    @BeforeEach
    void open() {
        path = directory.resolve("hitchline.jsonl");
        store = FileStore.open(path, Clocks.fixedClock("2025-06-23T09:00:00Z"), IdSources.sequentialIds(), true);
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

    private Recorder memoryContext() {
        MemoryStore memory = MemoryStore.create(Clocks.fixedClock("2025-06-23T09:00:00Z"), IdSources.randomIds());
        return Context.recordingContext(memory, Config.read(Map.of()).withStore(StoreKind.MEMORY));
    }

    @Nested
    class TheDoctor {

        @Test
        void checksTheStoreTheClockAndTheJournal() {
            assertThat(run("doctor", "run")).isEqualTo(Context.EXIT_OK);
            String text = context.text();
            assertThat(text).contains("store");
            assertThat(text).contains("clock");
            assertThat(text).contains("journal");
            assertThat(text).contains("lock");
        }

        @Test
        void saysWhoHoldsTheWriterLock() {
            run("doctor", "run");
            assertThat(context.text()).contains("held by pid");
        }

        @Test
        void countsTheCheckInsItVerified() {
            store.members().save(Fixtures.aMember());
            context.clear();
            run("doctor", "run");
            assertThat(context.text()).contains("0 check ins across 1 members");
        }

        @Test
        void saysWhenTheJournalIsWorthCompacting() {
            store.members().save(Fixtures.aMember());
            store.members().save(Fixtures.aMember(o -> o.displayName("Ada Nkemdirim")));
            context.clear();
            run("doctor", "run");
            assertThat(context.text()).contains("worth compacting");
        }

        @Test
        void leavesTheJournalChecksOutForAMemoryStore() {
            context = memoryContext();
            run("doctor", "run");
            assertThat(context.text()).doesNotContain("journal");
            assertThat(context.text()).doesNotContain("writer lock");
            assertThat(context.text()).contains("memory only");
        }

        @Test
        void readsAJournalItWasPointedAt() {
            context = memoryContext();
            run("doctor", "run", "--path", path.toString());
            assertThat(context.text()).contains("journal");
        }
    }
}
