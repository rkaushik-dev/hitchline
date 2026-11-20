package io.github.rkaushikdev.hitchline.infra.store;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Json;
import io.github.rkaushikdev.hitchline.util.MalformedJsonException;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * An append only journal, one JSON object per line, in UTF-8. Every change to the store is a line
 * here; the state is whatever you get by replaying them in order. There is no database because
 * there is no database available on the boxes this runs on, and a file that only ever grows is
 * easy to reason about. Each append is written before it returns, so nothing is acknowledged
 * before it is in the file.
 */
public final class Journal implements AutoCloseable {

    private final Path path;
    private long seq;
    private FileChannel handle;

    private Journal(Path path, long seq) throws IOException {
        this.path = path;
        this.seq = seq;
        this.handle = openForAppend(path);
    }

    private static FileChannel openForAppend(Path path) throws IOException {
        return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    /** Opens the journal at {@code path}, creating it and its directory when they are not there. */
    public static Journal open(Path path) {
        if (Texts.trim(path.toString()).isEmpty()) {
            throw ValidationException.field("path", "must not be empty");
        }
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return new Journal(path, read(path).lastSeq());
        } catch (IOException failure) {
            throw new UncheckedIOException("could not open the journal at " + path, failure);
        }
    }

    public Path path() {
        return path;
    }

    private static Optional<JournalEntry> parseLine(String line) {
        String trimmed = Texts.trim(line);
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        try {
            return JournalEntry.fromJson(Json.parse(trimmed));
        } catch (MalformedJsonException unreadable) {
            return Optional.empty();
        }
    }

    /** Reads the journal at {@code path}; an absent file reads as empty. */
    public static ReadReport read(Path path) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException absent) {
            return new ReadReport(List.of(), 0, 0);
        } catch (IOException failure) {
            throw new UncheckedIOException("could not read the journal at " + path, failure);
        }
        List<JournalEntry> entries = new ArrayList<>();
        int skipped = 0;
        for (String line : Texts.split(text, "\n")) {
            if (Texts.trim(line).isEmpty()) {
                continue;
            }
            Optional<JournalEntry> entry = parseLine(line);
            if (entry.isEmpty()) {
                skipped += 1;
                continue;
            }
            entries.add(entry.get());
        }
        long lastSeq = entries.stream().mapToLong(JournalEntry::seq).max().orElse(0);
        return new ReadReport(entries, skipped, Math.max(0, lastSeq));
    }

    public ReadReport read() {
        return read(path);
    }

    private FileChannel writer() throws IOException {
        if (handle == null) {
            handle = openForAppend(path);
        }
        return handle;
    }

    private void write(String body) {
        if (body.isEmpty()) {
            return;
        }
        try {
            ByteBuffer bytes = ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8));
            FileChannel file = writer();
            while (bytes.hasRemaining()) {
                file.write(bytes);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("could not append to the journal at " + path, failure);
        }
    }

    /** Appends one line and answers the entry it wrote. */
    public synchronized JournalEntry append(RecordKind kind, Operation op, JsonNode row, String at) {
        return appendMany(List.of(new PendingWrite(kind, op, row, at))).get(0);
    }

    /** Appends several lines with one write rather than one each. */
    public synchronized List<JournalEntry> appendMany(List<PendingWrite> rows) {
        List<JournalEntry> entries = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        long next = seq;
        for (PendingWrite item : rows) {
            next += 1;
            JournalEntry entry = new JournalEntry(next, item.at(), item.kind(), item.op(), item.row());
            entries.add(entry);
            body.append(Json.stringify(entry.toJson())).append('\n');
        }
        write(body.toString());
        seq = next;
        return entries;
    }

    /**
     * Rewrites the file from a fresh set of entries, as compaction and restore do. The new set is
     * written out beside the journal first, then over the journal itself, and the sequence carries
     * on from the highest one written.
     */
    public synchronized void rewrite(List<JournalEntry> entries) {
        Path temporary = path.resolveSibling(path.getFileName() + ".rewrite");
        StringBuilder text = new StringBuilder();
        for (JournalEntry entry : entries) {
            text.append(Json.stringify(entry.toJson())).append('\n');
        }
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            if (handle != null) {
                handle.close();
                handle = null;
            }
            Files.writeString(path, text, StandardCharsets.UTF_8);
            Files.deleteIfExists(temporary);
        } catch (IOException failure) {
            throw new UncheckedIOException("could not rewrite the journal at " + path, failure);
        }
        seq = Math.max(0, entries.stream().mapToLong(JournalEntry::seq).max().orElse(0));
    }

    /** Pushes everything written so far to the disk itself. */
    public synchronized void flush() {
        if (handle == null) {
            return;
        }
        try {
            handle.force(true);
        } catch (IOException failure) {
            throw new UncheckedIOException("could not flush the journal at " + path, failure);
        }
    }

    @Override
    public synchronized void close() {
        if (handle == null) {
            return;
        }
        FileChannel closing = handle;
        handle = null;
        try {
            closing.close();
        } catch (IOException failure) {
            throw new UncheckedIOException("could not close the journal at " + path, failure);
        }
    }

    /** The last write for each key among entries of {@code kind}, which is the state the store loads. */
    public static Map<String, JsonNode> fold(
            List<JournalEntry> entries, RecordKind kind, Function<JsonNode, String> keyOf) {
        Map<String, JsonNode> state = new LinkedHashMap<>();
        for (JournalEntry entry : entries) {
            if (entry.kind() != kind) {
                continue;
            }
            String key = keyOf.apply(entry.row());
            if (entry.op() == Operation.DELETE) {
                state.remove(key);
                continue;
            }
            state.put(key, entry.row());
        }
        return state;
    }

    /** Every row written for {@code kind}, for the tables that are lists rather than maps. */
    public static List<JsonNode> collect(List<JournalEntry> entries, RecordKind kind) {
        return entries.stream()
                .filter(entry -> entry.kind() == kind && entry.op() == Operation.PUT)
                .map(JournalEntry::row)
                .toList();
    }
}
