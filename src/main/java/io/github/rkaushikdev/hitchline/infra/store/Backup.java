package io.github.rkaushikdev.hitchline.infra.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rkaushikdev.hitchline.domain.ConflictException;
import io.github.rkaushikdev.hitchline.domain.NotFoundException;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Json;
import io.github.rkaushikdev.hitchline.util.MalformedJsonException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A backup: the compacted journal plus a header, which means it is also a perfectly good journal,
 * and restoring is writing it back out. On disk it is one JSON document, indented by two spaces,
 * with a final newline.
 *
 * @param version the backup format, which this build writes and reads as 1
 * @param takenAt when it was taken
 * @param entries the surviving journal entries, numbered from one
 */
public record Backup(int version, String takenAt, List<JournalEntry> entries) {

    public static final int BACKUP_VERSION = 1;

    public Backup {
        entries = List.copyOf(entries);
    }

    /** A backup of the journal as it stands. */
    public static Backup take(Journal journal, String takenAt) {
        ReadReport report = journal.read();
        return new Backup(BACKUP_VERSION, takenAt, Compaction.renumber(Compaction.survivors(report.entries())));
    }

    /** The backup as the document written to disk. */
    public ObjectNode toJson() {
        ObjectNode document = Json.object();
        document.put("version", version);
        document.put("takenAt", takenAt);
        ArrayNode listed = document.putArray("entries");
        entries.forEach(entry -> listed.add(entry.toJson()));
        return document;
    }

    /** Writes the backup to {@code path}, creating its directory. */
    public void write(Path path) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, Json.pretty(toJson(), 2) + "\n", StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("could not write the backup at " + path, failure);
        }
    }

    private static JournalEntry checkEntry(JsonNode value, int index) {
        if (value == null || !value.isObject()) {
            throw ValidationException.field("entries", "has a non object at position " + index);
        }
        JsonNode kind = value.get("kind");
        if (kind == null || !kind.isTextual() || !RecordKind.isRecordKind(kind.textValue())) {
            throw ValidationException.field("entries", "has an unknown kind at position " + index);
        }
        JsonNode op = value.get("op");
        if (op == null || !op.isTextual() || Operation.fromWire(op.textValue()).isEmpty()) {
            throw ValidationException.field("entries", "has an unknown operation at position " + index);
        }
        JsonNode seq = value.get("seq");
        JsonNode at = value.get("at");
        if (seq == null || !seq.isIntegralNumber() || at == null || !at.isTextual()) {
            throw ValidationException.field("entries", "is missing a header at position " + index);
        }
        JsonNode row = value.get("row");
        if (row == null || !(row.isObject() || row.isArray() || row.isNull())) {
            throw ValidationException.field("entries", "has no row at position " + index);
        }
        return new JournalEntry(
                seq.longValue(),
                at.textValue(),
                RecordKind.fromWire(kind.textValue()).orElseThrow(),
                Operation.fromWire(op.textValue()).orElseThrow(),
                row);
    }

    /** Reads a backup file, refusing anything that is not one this build writes. */
    public static Backup read(Path path) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException absent) {
            throw new NotFoundException("backup file", path.toString());
        } catch (IOException failure) {
            throw new UncheckedIOException("could not read the backup at " + path, failure);
        }
        JsonNode parsed;
        try {
            parsed = Json.parse(text);
        } catch (MalformedJsonException notJson) {
            throw ValidationException.field("backup", "at " + path + " is not json");
        }
        if (!parsed.isObject() && !parsed.isArray()) {
            throw ValidationException.field("backup", "at " + path + " is not an object");
        }
        JsonNode version = parsed.get("version");
        if (version == null || !version.isNumber() || version.doubleValue() != BACKUP_VERSION) {
            String written = version == null ? "undefined" : Codec.keyText(parsed, "version");
            throw ValidationException.field("version", "is " + written + ", and this build reads " + BACKUP_VERSION);
        }
        JsonNode takenAt = parsed.get("takenAt");
        if (takenAt == null || !takenAt.isTextual()) {
            throw ValidationException.field("takenAt", "is missing from the backup");
        }
        JsonNode listed = parsed.get("entries");
        if (listed == null || !listed.isArray()) {
            throw ValidationException.field("entries", "is missing from the backup");
        }
        List<JournalEntry> entries = new ArrayList<>();
        for (int index = 0; index < listed.size(); index += 1) {
            entries.add(checkEntry(listed.get(index), index));
        }
        return new Backup(BACKUP_VERSION, takenAt.textValue(), entries);
    }

    /** Writes the backup over {@code journal}, which must be empty unless {@code force} is given. */
    public RestoreReport restore(Journal journal, boolean force) {
        ReadReport current = journal.read();
        if (!current.entries().isEmpty() && !force) {
            throw new ConflictException(journal.path() + " already holds "
                    + current.entries().size() + " entries, pass force to replace them");
        }
        journal.rewrite(Compaction.renumber(entries));
        return new RestoreReport(entries.size(), takenAt);
    }

    /** How many entries of each kind the backup holds, in kind name order. */
    public Map<RecordKind, Integer> counts() {
        Map<RecordKind, Integer> counts =
                new TreeMap<>((left, right) -> left.wire().compareTo(right.wire()));
        for (JournalEntry entry : entries) {
            counts.merge(entry.kind(), 1, Integer::sum);
        }
        return counts;
    }

    /** One line, such as {@code 3 entries taken 2025-...: habit 1, member 2}. */
    public String describe() {
        List<String> parts = new ArrayList<>();
        counts().forEach((kind, count) -> parts.add(kind.wire() + " " + count));
        return entries.size() + " entries taken " + takenAt + ": " + String.join(", ", parts);
    }
}
