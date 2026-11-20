package io.github.rkaushikdev.hitchline.infra.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Compaction. The journal only grows, and a habit edited weekly leaves a line behind every time.
 * Compacting keeps the last write for each record and drops everything the store would not load
 * anyway.
 */
public final class Compaction {

    private Compaction() {}

    /** The identity of a row for the kinds where later writes replace earlier ones. */
    private static Optional<String> keyFor(RecordKind kind, JsonNode row) {
        return switch (kind) {
            case MEMBER, HABIT, CHECKIN, CREW -> Optional.of(Codec.keyText(row, "id"));
            case SHARING, POLICY -> Optional.of(Codec.keyText(row, "memberId"));
            case MEMBERSHIP -> Optional.of(Codec.keyText(row, "crewId") + "|" + Codec.keyText(row, "memberId"));
            case OVERRIDE, SHIELD -> Optional.empty();
        };
    }

    /**
     * Walks the entries once and returns the ones worth keeping, in their original order. Entries
     * for kinds that are append only lists, such as overrides and shields, are all kept.
     */
    public static List<JournalEntry> survivors(List<JournalEntry> entries) {
        Map<String, Long> lastSeqByKey = new HashMap<>();
        Set<String> deleted = new HashSet<>();
        for (JournalEntry entry : entries) {
            Optional<String> key = keyFor(entry.kind(), entry.row());
            if (key.isEmpty()) {
                continue;
            }
            String full = entry.kind().wire() + ":" + key.get();
            if (entry.op() == Operation.DELETE) {
                deleted.add(full);
                lastSeqByKey.remove(full);
                continue;
            }
            deleted.remove(full);
            lastSeqByKey.put(full, entry.seq());
        }
        List<JournalEntry> kept = new ArrayList<>();
        for (JournalEntry entry : entries) {
            Optional<String> key = keyFor(entry.kind(), entry.row());
            if (key.isEmpty()) {
                if (entry.op() == Operation.PUT) {
                    kept.add(entry);
                }
                continue;
            }
            String full = entry.kind().wire() + ":" + key.get();
            Long last = lastSeqByKey.get(full);
            if (!deleted.contains(full) && last != null && last == entry.seq()) {
                kept.add(entry);
            }
        }
        return kept;
    }

    /** Renumbers from one so a compacted file reads as if written in one go. */
    public static List<JournalEntry> renumber(List<JournalEntry> entries) {
        List<JournalEntry> numbered = new ArrayList<>();
        for (int index = 0; index < entries.size(); index += 1) {
            numbered.add(entries.get(index).withSeq(index + 1L));
        }
        return numbered;
    }

    /** Rewrites the journal with only its survivors, renumbered. */
    public static CompactionReport compact(Journal journal) {
        ReadReport report = journal.read();
        List<JournalEntry> kept = renumber(survivors(report.entries()));
        journal.rewrite(kept);
        return new CompactionReport(
                report.entries().size(), kept.size(), report.entries().size() - kept.size(), report.skipped());
    }

    /** What compacting would do, without writing anything. */
    public static CompactionReport estimate(Journal journal) {
        ReadReport report = journal.read();
        List<JournalEntry> kept = survivors(report.entries());
        return new CompactionReport(
                report.entries().size(), kept.size(), report.entries().size() - kept.size(), report.skipped());
    }
}
