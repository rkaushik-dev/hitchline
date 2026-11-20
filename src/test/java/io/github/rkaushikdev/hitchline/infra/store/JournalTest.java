package io.github.rkaushikdev.hitchline.infra.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rkaushikdev.hitchline.util.Json;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalTest {

    private static final String AT = "2025-01-10T09:00:00.000Z";

    @TempDir
    Path directory;

    private Path path;
    private Journal journal;

    @BeforeEach
    void open() {
        path = directory.resolve("nested").resolve("store.jsonl");
        journal = Journal.open(path);
    }

    @AfterEach
    void close() {
        journal.close();
    }

    private static ObjectNode row(String id) {
        return Json.object().put("id", id);
    }

    private static ObjectNode row(String id, int version) {
        return Json.object().put("id", id).put("v", version);
    }

    private List<String> lines() throws IOException {
        return Texts.split(Texts.trim(Files.readString(path, StandardCharsets.UTF_8)), "\n");
    }

    @Nested
    class RecordKinds {

        @Test
        void knowsTheOnesItStores() {
            assertThat(RecordKind.isRecordKind("checkin")).isTrue();
            assertThat(RecordKind.isRecordKind("invoice")).isFalse();
        }
    }

    @Nested
    class Appending {

        @Test
        void numbersEntriesFromOne() {
            JournalEntry first = journal.append(RecordKind.MEMBER, Operation.PUT, row("mem_1"), AT);
            JournalEntry second = journal.append(RecordKind.MEMBER, Operation.PUT, row("mem_2"), AT);
            assertThat(first.seq()).isEqualTo(1);
            assertThat(second.seq()).isEqualTo(2);
        }

        @Test
        void writesOneJsonObjectPerLine() throws IOException {
            journal.append(RecordKind.MEMBER, Operation.PUT, row("mem_1"), AT);
            journal.append(RecordKind.HABIT, Operation.PUT, row("hab_1"), AT);
            assertThat(lines()).hasSize(2);
        }

        @Test
        void createsTheDirectoryItWasPointedAt() {
            assertThat(Journal.read(path).entries()).isEmpty();
            assertThat(Files.isDirectory(path.getParent())).isTrue();
        }

        @Test
        void readsBackWhatItWrote() {
            journal.append(
                    RecordKind.MEMBER,
                    Operation.PUT,
                    Json.object().put("id", "mem_1").put("displayName", "Ada"),
                    AT);
            ReadReport report = journal.read();
            assertThat(report.entries()).hasSize(1);
            assertThat(report.entries().get(0).row())
                    .isEqualTo(Json.object().put("id", "mem_1").put("displayName", "Ada"));
            assertThat(report.entries().get(0).kind()).isEqualTo(RecordKind.MEMBER);
        }
    }

    @Nested
    class Reopening {

        @Test
        void carriesOnNumberingWhereItLeftOff() {
            journal.append(RecordKind.MEMBER, Operation.PUT, row("mem_1"), AT);
            journal.close();
            Journal reopened = Journal.open(path);
            JournalEntry next = reopened.append(RecordKind.MEMBER, Operation.PUT, row("mem_2"), AT);
            reopened.close();
            assertThat(next.seq()).isEqualTo(2);
        }

        @Test
        void readsAnAbsentFileAsEmpty() {
            assertThat(Journal.read(directory.resolve("missing.jsonl"))).isEqualTo(new ReadReport(List.of(), 0, 0));
        }
    }

    @Nested
    class DamagedLines {

        @Test
        void countsWhatItCouldNotReadRatherThanThrowing() throws IOException {
            journal.append(RecordKind.MEMBER, Operation.PUT, row("mem_1"), AT);
            journal.close();
            String good = Files.readString(path, StandardCharsets.UTF_8);
            Files.writeString(path, good + "not json at all\n{\"seq\":2}\n", StandardCharsets.UTF_8);
            ReadReport report = Journal.read(path);
            assertThat(report.entries()).hasSize(1);
            assertThat(report.skipped()).isEqualTo(2);
            journal = Journal.open(path);
        }

        @Test
        void skipsALineWithAnUnknownKind() throws IOException {
            ObjectNode line = Json.object()
                    .put("seq", 1)
                    .put("at", AT)
                    .put("kind", "invoice")
                    .put("op", "put");
            line.putObject("row");
            Files.writeString(path, Json.stringify(line) + "\n", StandardCharsets.UTF_8);
            ReadReport report = Journal.read(path);
            assertThat(report.entries()).isEmpty();
            assertThat(report.skipped()).isEqualTo(1);
        }

        @Test
        void ignoresBlankLinesWithoutCountingThem() throws IOException {
            Files.writeString(path, "\n\n", StandardCharsets.UTF_8);
            assertThat(Journal.read(path).skipped()).isZero();
        }
    }

    @Nested
    class Rewriting {

        @Test
        void replacesTheFileAndResetsTheSequence() {
            journal.append(RecordKind.MEMBER, Operation.PUT, row("mem_1"), AT);
            journal.append(RecordKind.MEMBER, Operation.PUT, row("mem_2"), AT);
            ReadReport report = journal.read();
            journal.rewrite(report.entries().subList(1, 2));
            ReadReport after = journal.read();
            assertThat(after.entries()).hasSize(1);
            assertThat(after.entries().get(0).row()).isEqualTo(row("mem_2"));
        }

        @Test
        void canEmptyTheFile() {
            journal.append(RecordKind.MEMBER, Operation.PUT, row("mem_1"), AT);
            journal.rewrite(List.of());
            assertThat(journal.read().entries()).isEmpty();
            assertThat(journal.append(RecordKind.MEMBER, Operation.PUT, row("mem_9"), AT)
                            .seq())
                    .isEqualTo(1);
        }
    }

    @Nested
    class Folding {

        private final List<JournalEntry> entries = List.of(
                new JournalEntry(1, AT, RecordKind.MEMBER, Operation.PUT, row("mem_1", 1)),
                new JournalEntry(2, AT, RecordKind.MEMBER, Operation.PUT, row("mem_1", 2)),
                new JournalEntry(3, AT, RecordKind.MEMBER, Operation.PUT, row("mem_2", 1)),
                new JournalEntry(4, AT, RecordKind.HABIT, Operation.PUT, row("hab_1")),
                new JournalEntry(5, AT, RecordKind.MEMBER, Operation.DELETE, row("mem_2")));
        private final Function<JsonNode, String> keyOf = item -> item.get("id").textValue();

        @Test
        void keepsTheLastWriteForEachKey() {
            Map<String, JsonNode> state = Journal.fold(entries, RecordKind.MEMBER, keyOf);
            assertThat(state.get("mem_1")).isEqualTo(row("mem_1", 2));
        }

        @Test
        void honoursADelete() {
            assertThat(Journal.fold(entries, RecordKind.MEMBER, keyOf)).doesNotContainKey("mem_2");
        }

        @Test
        void ignoresOtherKinds() {
            assertThat(Journal.fold(entries, RecordKind.MEMBER, keyOf)).hasSize(1);
            assertThat(Journal.fold(entries, RecordKind.HABIT, keyOf)).hasSize(1);
        }

        @Test
        void collectsEveryLiveRowOfAKind() {
            assertThat(Journal.collect(entries, RecordKind.MEMBER)).hasSize(3);
            assertThat(Journal.collect(entries, RecordKind.HABIT)).hasSize(1);
        }
    }

    @Nested
    class WritingSeveralAtOnce {

        @Test
        void numbersThemInOrderAndWritesOneLineEach() throws IOException {
            List<JournalEntry> written = journal.appendMany(List.of(
                    new PendingWrite(RecordKind.MEMBER, Operation.PUT, row("mem_1"), AT),
                    new PendingWrite(RecordKind.HABIT, Operation.PUT, row("hab_1"), AT)));
            assertThat(written.stream().map(JournalEntry::seq)).containsExactly(1L, 2L);
            assertThat(lines()).hasSize(2);
        }

        @Test
        void carriesOnNumberingAfterASingleAppend() {
            journal.append(RecordKind.MEMBER, Operation.PUT, row("mem_1"), AT);
            List<JournalEntry> written =
                    journal.appendMany(List.of(new PendingWrite(RecordKind.HABIT, Operation.PUT, row("hab_1"), AT)));
            assertThat(written.get(0).seq()).isEqualTo(2);
        }

        @Test
        void writesNothingForAnEmptyList() {
            assertThat(journal.appendMany(List.of())).isEmpty();
            assertThat(journal.read().entries()).isEmpty();
        }

        @Test
        void readsBackEverythingABurstOfAppendsWrote() {
            CompletableFuture.allOf(
                            CompletableFuture.runAsync(
                                    () -> journal.append(RecordKind.MEMBER, Operation.PUT, row("mem_1"), AT)),
                            CompletableFuture.runAsync(
                                    () -> journal.append(RecordKind.MEMBER, Operation.PUT, row("mem_2"), AT)),
                            CompletableFuture.runAsync(
                                    () -> journal.append(RecordKind.MEMBER, Operation.PUT, row("mem_3"), AT)))
                    .join();
            ReadReport report = journal.read();
            assertThat(report.entries()).hasSize(3);
            assertThat(report.entries().stream().map(JournalEntry::seq)).containsExactly(1L, 2L, 3L);
        }

        @Test
        void keepsTheOrderABurstWasMadeIn() {
            journal.append(RecordKind.MEMBER, Operation.PUT, row("mem_1"), AT);
            journal.append(RecordKind.HABIT, Operation.PUT, row("hab_1"), AT);
            assertThat(journal.read().entries().stream().map(JournalEntry::kind))
                    .containsExactly(RecordKind.MEMBER, RecordKind.HABIT);
        }
    }
}
