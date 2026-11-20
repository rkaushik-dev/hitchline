package io.github.rkaushikdev.hitchline.infra.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rkaushikdev.hitchline.util.Json;
import java.util.Objects;
import java.util.Optional;

/**
 * One line of the journal: {@code {"seq":1,"at":"...","kind":"member","op":"put","row":{...}}}.
 *
 * @param seq the line's place in the journal, counting from one
 * @param at when it was written, as an ISO 8601 timestamp
 * @param kind the kind of record
 * @param op what the line does to it
 * @param row the record as stored
 */
public record JournalEntry(long seq, String at, RecordKind kind, Operation op, JsonNode row) {

    public JournalEntry {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(row, "row");
    }

    /** The entry as the JSON object a line holds, its keys in journal order. */
    public ObjectNode toJson() {
        ObjectNode line = Json.object();
        line.put("seq", seq);
        line.put("at", at);
        line.put("kind", kind.wire());
        line.put("op", op.wire());
        line.set("row", row);
        return line;
    }

    /** The same entry numbered {@code value}. */
    public JournalEntry withSeq(long value) {
        return new JournalEntry(value, at, kind, op, row);
    }

    /**
     * Reads a journal line's object: a whole number {@code seq}, a string {@code at}, a known
     * {@code kind}, {@code put} or {@code delete}, and a {@code row} that is an object, a list or
     * null. Anything else is not an entry.
     */
    static Optional<JournalEntry> fromJson(JsonNode value) {
        if (value == null || !value.isObject()) {
            return Optional.empty();
        }
        JsonNode seq = value.get("seq");
        JsonNode at = value.get("at");
        JsonNode kind = value.get("kind");
        JsonNode op = value.get("op");
        JsonNode row = value.get("row");
        if (seq == null || !seq.isIntegralNumber() || at == null || !at.isTextual()) {
            return Optional.empty();
        }
        if (kind == null || !kind.isTextual() || op == null || !op.isTextual()) {
            return Optional.empty();
        }
        Optional<RecordKind> knownKind = RecordKind.fromWire(kind.textValue());
        Optional<Operation> knownOp = Operation.fromWire(op.textValue());
        if (knownKind.isEmpty() || knownOp.isEmpty()) {
            return Optional.empty();
        }
        if (row == null || !(row.isObject() || row.isArray() || row.isNull())) {
            return Optional.empty();
        }
        return Optional.of(new JournalEntry(seq.longValue(), at.textValue(), knownKind.get(), knownOp.get(), row));
    }
}
