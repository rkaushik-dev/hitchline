package io.github.rkaushikdev.hitchline.imports;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reading comma separated files back in. Written as a state machine rather than a split on
 * commas, because the files that turn up have quoted notes in them with commas and newlines
 * inside, and a naive split silently loses rows. Lines may end in LF, CR or CRLF.
 */
public final class CsvReader {

    private final String text;
    private int index;

    private CsvReader(String text) {
        this.text = text;
    }

    /**
     * A file read as a header and keyed rows.
     *
     * @param headers the column names, trimmed and lower cased
     * @param rows each body line keyed by the column names, in column order
     */
    public record CsvTable(List<String> headers, List<Map<String, String>> rows) {

        public CsvTable {
            headers = List.copyOf(headers);
            rows = rows.stream()
                    .map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row)))
                    .toList();
        }

        /** Refuses a table that lacks any of the columns named. */
        public void requireColumns(List<String> wanted) {
            List<String> missing =
                    wanted.stream().filter(header -> !headers.contains(header)).toList();
            if (!missing.isEmpty()) {
                throw ValidationException.field("csv", "is missing the columns " + String.join(", ", missing));
            }
        }
    }

    private char at(int position) {
        return position < text.length() ? text.charAt(position) : '\0';
    }

    private boolean isAt(int position, char wanted) {
        return position < text.length() && text.charAt(position) == wanted;
    }

    private String readQuoted() {
        StringBuilder value = new StringBuilder();
        index += 1;
        while (index < text.length()) {
            char character = text.charAt(index);
            if (character == '"') {
                if (isAt(index + 1, '"')) {
                    value.append('"');
                    index += 2;
                    continue;
                }
                index += 1;
                return value.toString();
            }
            value.append(character);
            index += 1;
        }
        throw ValidationException.field("csv", "ends inside a quoted value");
    }

    private String readPlain() {
        StringBuilder value = new StringBuilder();
        while (index < text.length()) {
            char character = text.charAt(index);
            if (character == ',' || character == '\n' || character == '\r') {
                break;
            }
            if (character == '"') {
                throw ValidationException.field("csv", "has a quote in the middle of a value");
            }
            value.append(character);
            index += 1;
        }
        return value.toString();
    }

    private List<List<String>> rows() {
        List<List<String>> rows = new ArrayList<>();
        if (text.isEmpty()) {
            return rows;
        }
        List<String> row = new ArrayList<>();
        while (true) {
            row.add(isAt(index, '"') ? readQuoted() : readPlain());
            if (index >= text.length()) {
                rows.add(row);
                return rows;
            }
            if (at(index) == ',') {
                index += 1;
                if (index >= text.length()) {
                    row.add("");
                    rows.add(row);
                    return rows;
                }
                continue;
            }
            if (at(index) == '\n' || at(index) == '\r') {
                if (isAt(index, '\r')) {
                    index += 1;
                }
                if (isAt(index, '\n')) {
                    index += 1;
                }
                rows.add(row);
                row = new ArrayList<>();
                if (index >= text.length()) {
                    return rows;
                }
                continue;
            }
            throw ValidationException.field("csv", "has an unexpected character at " + index);
        }
    }

    /** Every line of the text as its cells. Empty text has no lines. */
    public static List<List<String>> parse(String text) {
        return new CsvReader(text).rows();
    }

    /**
     * Reads the first line as a header and keys every row by it. Header names are trimmed and
     * lower cased and may not repeat; a blank line is skipped; every other line must have as many
     * cells as the header.
     */
    public static CsvTable readTable(String text) {
        List<List<String>> rows = parse(text);
        if (rows.isEmpty() || rows.get(0).isEmpty()) {
            throw ValidationException.field("csv", "has no header line");
        }
        List<String> headers = rows.get(0).stream()
                .map(header -> Texts.trim(header).toLowerCase(Locale.ROOT))
                .toList();
        Set<String> seen = new HashSet<>();
        for (String header : headers) {
            if (!seen.add(header)) {
                throw ValidationException.field("csv", "names the column " + header + " twice");
            }
        }
        List<Map<String, String>> body = new ArrayList<>();
        for (int line = 1; line < rows.size(); line += 1) {
            List<String> cells = rows.get(line);
            if (cells.size() == 1 && Texts.trim(cells.get(0)).isEmpty()) {
                continue;
            }
            if (cells.size() != headers.size()) {
                throw ValidationException.field(
                        "csv", "line " + (line + 1) + " has " + cells.size() + " cells, not " + headers.size());
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int position = 0; position < headers.size(); position += 1) {
                row.put(headers.get(position), cells.get(position));
            }
            body.add(row);
        }
        return new CsvTable(headers, body);
    }
}
