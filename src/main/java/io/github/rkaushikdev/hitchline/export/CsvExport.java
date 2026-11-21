package io.github.rkaushikdev.hitchline.export;

import io.github.rkaushikdev.hitchline.app.RosterService.RosterDay;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Numbers;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Comma separated output for the people who will put it in a spreadsheet no matter what the api
 * offers. Quoting follows RFC 4180: double quotes around anything with a comma, a quote or a line
 * break, quotes doubled inside, and every line ending in CRLF.
 */
public final class CsvExport {

    /**
     * One column of a file.
     *
     * @param header the column's name on the first line
     * @param get the cell for a row
     * @param <T> the rows the column reads
     */
    public record CsvColumn<T>(String header, Function<T, String> get) {}

    private static final Pattern NEEDS_QUOTES = Pattern.compile("[\",\r\n]");

    /** The columns of a check in export. */
    public static final List<CsvColumn<ResolvedCheckIn>> CHECK_IN_COLUMNS = List.of(
            new CsvColumn<>("id", row -> row.checkIn().id()),
            new CsvColumn<>("habit_id", row -> row.checkIn().habitId()),
            new CsvColumn<>("member_id", row -> row.checkIn().memberId()),
            new CsvColumn<>("at", row -> Zones.formatInstant(row.checkIn().at())),
            new CsvColumn<>("rotation_day", row -> row.day().date().toString()),
            new CsvColumn<>("role", row -> row.day().role().wire()),
            new CsvColumn<>("zone", row -> row.day().zone()),
            new CsvColumn<>(
                    "reading",
                    row -> row.checkIn().reading().isPresent()
                            ? Numbers.format(row.checkIn().reading().getAsDouble())
                            : ""),
            new CsvColumn<>("source", row -> row.checkIn().source().wire()),
            new CsvColumn<>("note", row -> row.checkIn().note()));

    private static final List<CsvColumn<ObligationResult>> RESULT_COLUMNS = List.of(
            new CsvColumn<>("habit_id", row -> row.obligation().habitId()),
            new CsvColumn<>("scope", row -> row.obligation().scope().wire()),
            new CsvColumn<>("window", row -> row.obligation().window().toString()),
            new CsvColumn<>("due_on", row -> row.obligation().dueOn().toString()),
            new CsvColumn<>("times", row -> Integer.toString(row.obligation().times())),
            new CsvColumn<>("count", row -> Integer.toString(row.count())),
            new CsvColumn<>(
                    "total",
                    row -> row.total().isPresent() ? Numbers.format(row.total().getAsDouble()) : ""),
            new CsvColumn<>("status", row -> row.status().wire()));

    private static final List<CsvColumn<RosterDay>> ROSTER_COLUMNS = List.of(
            new CsvColumn<>("date", row -> row.date().toString()),
            new CsvColumn<>("role", row -> row.role().wire()),
            new CsvColumn<>("source", row -> row.source().wire()),
            new CsvColumn<>("zone", RosterDay::zone));

    private CsvExport() {}

    /** One cell, quoted when it has to be. */
    public static String csvCell(String value) {
        if (!NEEDS_QUOTES.matcher(value).find()) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    public static String csvRow(List<String> cells) {
        return String.join(",", cells.stream().map(CsvExport::csvCell).toList());
    }

    /** A header line and one line per row, each ending in CRLF. */
    public static <T> String toCsv(List<T> rows, List<CsvColumn<T>> columns) {
        if (columns.isEmpty()) {
            throw ValidationException.field("columns", "must name at least one column");
        }
        List<String> lines = new ArrayList<>();
        lines.add(csvRow(columns.stream().map(CsvColumn::header).toList()));
        for (T row : rows) {
            lines.add(csvRow(
                    columns.stream().map(column -> column.get().apply(row)).toList()));
        }
        return String.join("\r\n", lines) + "\r\n";
    }

    public static String checkInsToCsv(List<ResolvedCheckIn> rows) {
        return toCsv(rows, CHECK_IN_COLUMNS);
    }

    public static String resultsToCsv(List<ObligationResult> rows) {
        return toCsv(rows, RESULT_COLUMNS);
    }

    public static String rosterToCsv(List<RosterDay> rows) {
        return toCsv(rows, ROSTER_COLUMNS);
    }
}
