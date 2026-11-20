package io.github.rkaushikdev.hitchline.infra.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Json;
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

class BackupTest {

    private static final String AT = "2025-01-10T09:00:00.000Z";

    @TempDir
    Path directory;

    private Journal journal;

    @BeforeEach
    void open() {
        journal = Journal.open(directory.resolve("store.jsonl"));
    }

    @AfterEach
    void close() {
        journal.close();
    }

    private void seed() {
        journal.append(
                RecordKind.MEMBER,
                Operation.PUT,
                Json.object().put("id", "mem_1").put("v", 1),
                AT);
        journal.append(
                RecordKind.MEMBER,
                Operation.PUT,
                Json.object().put("id", "mem_1").put("v", 2),
                AT);
        journal.append(RecordKind.HABIT, Operation.PUT, Json.object().put("id", "hab_1"), AT);
        journal.append(
                RecordKind.OVERRIDE,
                Operation.PUT,
                Json.object().put("memberId", "mem_1").put("range", "a"),
                AT);
    }

    private Path fileWith(String name, String text) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    @Nested
    class TakingABackup {

        @Test
        void holdsTheCompactedEntries() {
            seed();
            Backup backup = Backup.take(journal, AT);
            assertThat(backup.entries()).hasSize(3);
            assertThat(backup.version()).isEqualTo(Backup.BACKUP_VERSION);
            assertThat(backup.takenAt()).isEqualTo(AT);
        }

        @Test
        void renumbersFromOne() {
            seed();
            assertThat(Backup.take(journal, AT).entries().stream().map(JournalEntry::seq))
                    .containsExactly(1L, 2L, 3L);
        }

        @Test
        void backsUpAnEmptyJournal() {
            assertThat(Backup.take(journal, AT).entries()).isEmpty();
        }
    }

    @Nested
    class WritingAndReadingABackupFile {

        @Test
        void roundTrips() {
            seed();
            Path file = directory.resolve("nested").resolve("backup.json");
            Backup.take(journal, AT).write(file);
            Backup back = Backup.read(file);
            assertThat(back.entries()).hasSize(3);
            assertThat(back.takenAt()).isEqualTo(AT);
        }

        @Test
        void writesReadableJson() throws IOException {
            seed();
            Path file = directory.resolve("backup.json");
            Backup.take(journal, AT).write(file);
            assertThat(Files.readString(file, StandardCharsets.UTF_8)).startsWith("{\n  \"version\"");
        }

        @Test
        void refusesAFileThatIsNotJson() throws IOException {
            Path file = fileWith("broken.json", "not json");
            assertThatThrownBy(() -> Backup.read(file)).hasMessageContaining("is not json");
        }

        @Test
        void refusesAVersionItDoesNotRead() throws IOException {
            Path file = fileWith("old.json", "{\"version\":99,\"takenAt\":\"" + AT + "\",\"entries\":[]}");
            assertThatThrownBy(() -> Backup.read(file)).hasMessageContaining("this build reads 1");
        }

        @Test
        void refusesAnEntryWithAnUnknownKind() throws IOException {
            Path file = fileWith(
                    "odd.json",
                    "{\"version\":1,\"takenAt\":\"" + AT + "\",\"entries\":[{\"seq\":1,\"at\":\"" + AT
                            + "\",\"kind\":\"invoice\",\"op\":\"put\",\"row\":{}}]}");
            assertThatThrownBy(() -> Backup.read(file)).isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesAnEntryWithNoRow() throws IOException {
            Path file = fileWith(
                    "odd.json",
                    "{\"version\":1,\"takenAt\":\"" + AT + "\",\"entries\":[{\"seq\":1,\"at\":\"" + AT
                            + "\",\"kind\":\"member\",\"op\":\"put\"}]}");
            assertThatThrownBy(() -> Backup.read(file)).hasMessageContaining("has no row at position 0");
        }
    }

    @Nested
    class Restoring {

        @Test
        void writesABackupIntoAnEmptyJournal() {
            seed();
            Backup backup = Backup.take(journal, AT);
            try (Journal target = Journal.open(directory.resolve("restored.jsonl"))) {
                RestoreReport report = backup.restore(target, false);
                assertThat(report.restored()).isEqualTo(3);
                assertThat(target.read().entries()).hasSize(3);
            }
        }

        @Test
        void refusesToWriteOverAJournalThatHoldsSomething() {
            seed();
            Backup backup = Backup.take(journal, AT);
            assertThatThrownBy(() -> backup.restore(journal, false)).isInstanceOf(ConflictException.class);
        }

        @Test
        void writesOverItWhenForced() {
            seed();
            Backup backup = Backup.take(journal, AT);
            assertThat(backup.restore(journal, true).restored()).isEqualTo(3);
            assertThat(journal.read().entries()).hasSize(3);
        }
    }

    @Nested
    class ReadingABackup {

        @Test
        void countsTheEntriesByKind() {
            seed();
            assertThat(Backup.take(journal, AT).counts())
                    .isEqualTo(Map.of(RecordKind.MEMBER, 1, RecordKind.HABIT, 1, RecordKind.OVERRIDE, 1));
        }

        @Test
        void describesItselfInOneLine() {
            seed();
            assertThat(Backup.take(journal, AT).describe())
                    .isEqualTo("3 entries taken " + AT + ": habit 1, member 1, override 1");
        }
    }
}
