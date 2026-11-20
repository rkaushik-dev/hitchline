package io.github.rkaushikdev.hitchline.infra.store;

import java.util.List;

/**
 * What reading a journal found.
 *
 * @param entries the lines that read as entries, in file order
 * @param skipped lines that could not be read, kept as a count rather than thrown away
 * @param lastSeq the highest sequence number seen, zero for an empty journal
 */
public record ReadReport(List<JournalEntry> entries, int skipped, long lastSeq) {

    public ReadReport {
        entries = List.copyOf(entries);
    }
}
