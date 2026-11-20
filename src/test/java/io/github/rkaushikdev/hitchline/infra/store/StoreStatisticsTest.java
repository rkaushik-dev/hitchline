package io.github.rkaushikdev.hitchline.infra.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreStatisticsTest {

    @TempDir
    Path directory;

    private static JournalEntry entry(RecordKind kind, String at) {
        return new JournalEntry(1, at, kind, Operation.PUT, Json.object());
    }

    private void backups(String... names) throws IOException {
        for (String name : names) {
            Files.writeString(directory.resolve(name), "{}", StandardCharsets.UTF_8);
        }
    }

    private List<String> listing() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).toList();
        }
    }

    @Nested
    class Statistics {

        private final List<JournalEntry> entries = List.of(
                entry(RecordKind.MEMBER, "2025-07-01T09:00:00.000Z"),
                entry(RecordKind.HABIT, "2025-07-01T10:00:00.000Z"),
                entry(RecordKind.CHECKIN, "2025-07-02T09:00:00.000Z"),
                entry(RecordKind.CHECKIN, "2025-07-03T09:00:00.000Z"));

        @Test
        void countsTheEntriesByKind() {
            assertThat(StoreStatistics.of(entries).byKind())
                    .isEqualTo(Map.of(RecordKind.MEMBER, 1, RecordKind.HABIT, 1, RecordKind.CHECKIN, 2));
        }

        @Test
        void namesTheFirstAndTheLast() {
            StoreStatistics stats = StoreStatistics.of(entries);
            assertThat(stats.firstAt()).hasValue("2025-07-01T09:00:00.000Z");
            assertThat(stats.lastAt()).hasValue("2025-07-03T09:00:00.000Z");
        }

        @Test
        void countsTheDaysSomethingWasWritten() {
            assertThat(StoreStatistics.of(entries).writers()).isEqualTo(3);
        }

        @Test
        void readsAnEmptyJournalWithoutInventingAnything() {
            assertThat(StoreStatistics.of(List.of()))
                    .isEqualTo(new StoreStatistics(0, Map.of(), Optional.empty(), Optional.empty(), 0));
        }

        @Test
        void readsBackInOneLine() {
            assertThat(StoreStatistics.of(entries).describe())
                    .isEqualTo("4 entries over 3 days: checkin 2, habit 1, member 1");
        }
    }

    @Nested
    class PruningBackups {

        @Test
        void keepsTheNewestFewByName() throws IOException {
            backups("2025-07-01.json", "2025-07-02.json", "2025-07-03.json");
            PruneReport report = StoreStatistics.pruneBackups(directory, 2, false);
            assertThat(report.kept()).containsExactly("2025-07-03.json", "2025-07-02.json");
            assertThat(report.removed()).containsExactly("2025-07-01.json");
            assertThat(listing()).hasSize(2);
        }

        @Test
        void leavesEverythingAloneWhenThereAreFewEnough() throws IOException {
            backups("2025-07-01.json");
            assertThat(StoreStatistics.pruneBackups(directory, 5, false).removed())
                    .isEmpty();
        }

        @Test
        void ignoresAnythingThatIsNotABackup() throws IOException {
            backups("2025-07-01.json", "2025-07-02.json");
            Files.writeString(directory.resolve("notes.txt"), "hello", StandardCharsets.UTF_8);
            StoreStatistics.pruneBackups(directory, 1, false);
            assertThat(listing()).contains("notes.txt");
        }

        @Test
        void saysWhatItWouldDoWithoutDoingIt() throws IOException {
            backups("2025-07-01.json", "2025-07-02.json");
            PruneReport report = StoreStatistics.pruneBackups(directory, 1, true);
            assertThat(report.removed()).containsExactly("2025-07-01.json");
            assertThat(listing()).hasSize(2);
        }

        @Test
        void refusesToKeepNothing() {
            assertThatThrownBy(() -> StoreStatistics.pruneBackups(directory, 0, false))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    class SizesAndPaths {

        @Test
        void measuresEachFileItIsGiven() throws IOException {
            Path path = directory.resolve("one.json");
            Files.writeString(path, "{\"a\":1}", StandardCharsets.UTF_8);
            assertThat(StoreStatistics.backupSizes(List.of(path))).isEqualTo(Map.of("one.json", 7L));
        }

        @Test
        void putsBackupsBesideTheJournal() {
            assertThat(StoreStatistics.backupDirectoryFor(Path.of("/data/hitchline.jsonl")))
                    .isEqualTo(Path.of("/data", "backups"));
        }
    }
}
