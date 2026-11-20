package io.github.rkaushikdev.hitchline.infra.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rkaushikdev.hitchline.util.Json;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompactionTest {

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

    private static JournalEntry entry(long seq, RecordKind kind, Operation op, ObjectNode row) {
        return new JournalEntry(seq, AT, kind, op, row);
    }

    private static ObjectNode id(String value) {
        return Json.object().put("id", value);
    }

    private static ObjectNode id(String value, int version) {
        return Json.object().put("id", value).put("v", version);
    }

    private static ObjectNode membership(String crew, String member) {
        return Json.object().put("crewId", crew).put("memberId", member);
    }

    private static ObjectNode ranged(String range) {
        return Json.object().put("memberId", "mem_1").put("range", range);
    }

    private void append(ObjectNode row, RecordKind kind) {
        journal.append(kind, Operation.PUT, row, AT);
    }

    @Nested
    class Survivors {

        @Test
        void keepsOnlyTheLastWriteForAKeyedRecord() {
            List<JournalEntry> kept = Compaction.survivors(List.of(
                    entry(1, RecordKind.MEMBER, Operation.PUT, id("mem_1", 1)),
                    entry(2, RecordKind.MEMBER, Operation.PUT, id("mem_1", 2))));
            assertThat(kept).hasSize(1);
            assertThat(kept.get(0).row()).isEqualTo(id("mem_1", 2));
        }

        @Test
        void keepsOneEntryPerKey() {
            List<JournalEntry> kept = Compaction.survivors(List.of(
                    entry(1, RecordKind.MEMBER, Operation.PUT, id("mem_1")),
                    entry(2, RecordKind.MEMBER, Operation.PUT, id("mem_2")),
                    entry(3, RecordKind.MEMBER, Operation.PUT, id("mem_1"))));
            assertThat(kept.stream().map(JournalEntry::seq)).containsExactly(2L, 3L);
        }

        @Test
        void dropsARecordThatWasDeleted() {
            assertThat(Compaction.survivors(List.of(
                            entry(1, RecordKind.MEMBER, Operation.PUT, id("mem_1")),
                            entry(2, RecordKind.MEMBER, Operation.DELETE, id("mem_1")))))
                    .isEmpty();
        }

        @Test
        void keepsARecordWrittenAgainAfterADelete() {
            List<JournalEntry> kept = Compaction.survivors(List.of(
                    entry(1, RecordKind.MEMBER, Operation.PUT, id("mem_1")),
                    entry(2, RecordKind.MEMBER, Operation.DELETE, id("mem_1")),
                    entry(3, RecordKind.MEMBER, Operation.PUT, id("mem_1", 3))));
            assertThat(kept).hasSize(1);
            assertThat(kept.get(0).seq()).isEqualTo(3);
        }

        @Test
        void keepsEveryEntryOfAnAppendOnlyKind() {
            assertThat(Compaction.survivors(List.of(
                            entry(1, RecordKind.OVERRIDE, Operation.PUT, ranged("a")),
                            entry(2, RecordKind.OVERRIDE, Operation.PUT, ranged("b")),
                            entry(3, RecordKind.SHIELD, Operation.PUT, ranged("c")))))
                    .hasSize(3);
        }

        @Test
        void keysAMembershipOnTheCrewAndTheMemberTogether() {
            List<JournalEntry> kept = Compaction.survivors(List.of(
                    entry(1, RecordKind.MEMBERSHIP, Operation.PUT, membership("crw_1", "mem_1")),
                    entry(2, RecordKind.MEMBERSHIP, Operation.PUT, membership("crw_2", "mem_1")),
                    entry(
                            3,
                            RecordKind.MEMBERSHIP,
                            Operation.PUT,
                            membership("crw_1", "mem_1").put("to", "2025-06-30"))));
            assertThat(kept.stream().map(JournalEntry::seq)).containsExactly(2L, 3L);
        }
    }

    @Nested
    class Renumber {

        @Test
        void runsFromOneWithNoGaps() {
            List<JournalEntry> renumbered = Compaction.renumber(List.of(
                    entry(4, RecordKind.MEMBER, Operation.PUT, id("mem_1")),
                    entry(9, RecordKind.MEMBER, Operation.PUT, id("mem_2"))));
            assertThat(renumbered.stream().map(JournalEntry::seq)).containsExactly(1L, 2L);
        }

        @Test
        void leavesTheRestOfTheEntryAlone() {
            JournalEntry only = Compaction.renumber(List.of(entry(4, RecordKind.HABIT, Operation.PUT, id("hab_1"))))
                    .get(0);
            assertThat(only.kind()).isEqualTo(RecordKind.HABIT);
            assertThat(only.row()).isEqualTo(id("hab_1"));
        }
    }

    @Nested
    class CompactingARealJournal {

        @Test
        void rewritesTheFileAndReportsWhatItDropped() {
            append(id("mem_1", 1), RecordKind.MEMBER);
            append(id("mem_1", 2), RecordKind.MEMBER);
            append(id("hab_1"), RecordKind.HABIT);
            CompactionReport report = Compaction.compact(journal);
            assertThat(report.before()).isEqualTo(3);
            assertThat(report.after()).isEqualTo(2);
            assertThat(report.dropped()).isEqualTo(1);
            assertThat(journal.read().entries().stream().map(JournalEntry::seq)).containsExactly(1L, 2L);
        }

        @Test
        void carriesOnAppendingAfterACompaction() {
            append(id("mem_1", 1), RecordKind.MEMBER);
            append(id("mem_1", 2), RecordKind.MEMBER);
            Compaction.compact(journal);
            JournalEntry next = journal.append(RecordKind.MEMBER, Operation.PUT, id("mem_2"), AT);
            assertThat(next.seq()).isEqualTo(2);
            assertThat(journal.read().entries()).hasSize(2);
        }

        @Test
        void doesNothingToAnAlreadyTidyJournal() {
            append(id("mem_1"), RecordKind.MEMBER);
            assertThat(Compaction.compact(journal).dropped()).isZero();
        }

        @Test
        void estimatesWithoutWriting() {
            append(id("mem_1", 1), RecordKind.MEMBER);
            append(id("mem_1", 2), RecordKind.MEMBER);
            assertThat(Compaction.estimate(journal).dropped()).isEqualTo(1);
            assertThat(journal.read().entries()).hasSize(2);
        }
    }

    @Nested
    class WorthCompacting {

        @Test
        void isTrueOnceEnoughIsDeadWeight() {
            assertThat(new CompactionReport(10, 6, 4, 0).worthCompacting()).isTrue();
        }

        @Test
        void isFalseWhenLittleWouldBeSaved() {
            assertThat(new CompactionReport(10, 9, 1, 0).worthCompacting()).isFalse();
        }

        @Test
        void isFalseForAnEmptyJournal() {
            assertThat(new CompactionReport(0, 0, 0, 0).worthCompacting()).isFalse();
        }

        @Test
        void takesARatio() {
            assertThat(new CompactionReport(10, 9, 1, 0).worthCompacting(0.1)).isTrue();
        }
    }
}
