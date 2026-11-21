package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.app.CheckInsService.RecordCheckInCommand;
import io.github.rkaushikdev.hitchline.app.CheckInsService.RecordResult;
import io.github.rkaushikdev.hitchline.domain.DomainException;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.imports.CsvReader;
import io.github.rkaushikdev.hitchline.imports.CsvReader.CsvTable;
import io.github.rkaushikdev.hitchline.util.Numbers;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Bulk import of check ins from a CSV file with {@code habit_id} and {@code at} columns, and
 * optionally {@code reading}, {@code note} and {@code idempotency_key}. A rig that ran on paper for
 * a fortnight hands over a spreadsheet, and one bad line in it must not throw the other two hundred
 * away, so every row is reported on its own and the file is never atomic.
 */
public final class ImportService {

    private static final List<String> REQUIRED = List.of("habit_id", "at");

    private final Ports ports;
    private final CheckInsService checkIns;

    public ImportService(Ports ports) {
        this.ports = ports;
        this.checkIns = new CheckInsService(ports);
    }

    /**
     * A row that did not go in.
     *
     * @param line the line of the file, the header being line 1
     * @param message why
     */
    public record ImportFailure(int line, String message) {}

    /**
     * What an import did.
     *
     * @param read rows read
     * @param accepted rows stored, or that would be on a dry run
     * @param duplicates rows whose idempotency key was already used
     * @param failures rows refused
     */
    public record ImportReport(int read, int accepted, int duplicates, List<ImportFailure> failures) {

        public ImportReport {
            failures = List.copyOf(failures);
        }

        /** One line, such as {@code 3 read, 2 accepted, 0 already there, 1 refused}. */
        public String describe() {
            return read + " read, " + accepted + " accepted, " + duplicates + " already there, " + failures.size()
                    + " refused";
        }
    }

    /**
     * How to import.
     *
     * @param dryRun read and check every row without storing anything
     * @param source the source the rows are stored as: import, or supervisor
     */
    public record ImportOptions(boolean dryRun, CheckInSource source) {

        /** Store the rows, as imports. */
        public static final ImportOptions STORE = new ImportOptions(false, CheckInSource.IMPORT);
    }

    private static String cell(Map<String, String> row, String key) {
        return row.getOrDefault(key, "");
    }

    public ImportReport checkIns(String text) {
        return checkIns(text, ImportOptions.STORE);
    }

    public ImportReport checkIns(String text, ImportOptions options) {
        CsvTable table = CsvReader.readTable(text);
        table.requireColumns(REQUIRED);
        int accepted = 0;
        int duplicates = 0;
        List<ImportFailure> failures = new ArrayList<>();
        for (int index = 0; index < table.rows().size(); index += 1) {
            Map<String, String> row = table.rows().get(index);
            int line = index + 2;
            try {
                String reading = Texts.trim(cell(row, "reading"));
                String key = Texts.trim(cell(row, "idempotency_key"));
                if (!reading.isEmpty() && Double.isNaN(Numbers.parse(reading))) {
                    throw ValidationException.field("reading", "is not a number: " + reading);
                }
                String habitId = Ids.habitId(cell(row, "habit_id"));
                String note = Texts.trim(cell(row, "note"));
                RecordCheckInCommand command = new RecordCheckInCommand(
                        habitId,
                        Optional.of(cell(row, "at")),
                        reading.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(Numbers.parse(reading)),
                        note.isEmpty() ? Optional.empty() : Optional.of(note),
                        Optional.of(options.source()),
                        key.isEmpty() ? Optional.empty() : Optional.of(key));
                if (options.dryRun()) {
                    if (ports.habits().byId(habitId).isEmpty()) {
                        throw ValidationException.field("habit_id", "names a habit that does not exist: " + habitId);
                    }
                    accepted += 1;
                    continue;
                }
                RecordResult result = checkIns.record(command);
                if (result.accepted()) {
                    accepted += 1;
                } else {
                    duplicates += 1;
                }
            } catch (DomainException refused) {
                failures.add(new ImportFailure(line, refused.getMessage()));
            }
        }
        return new ImportReport(table.rows().size(), accepted, duplicates, failures);
    }
}
