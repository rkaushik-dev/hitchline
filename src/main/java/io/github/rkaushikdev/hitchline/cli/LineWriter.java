package io.github.rkaushikdev.hitchline.cli;

import java.io.Writer;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A writer that hands each complete line to a consumer, without its line ending, so help and usage
 * text reach the same context the commands print through. A line still open when the writer is
 * closed is handed over then.
 */
final class LineWriter extends Writer {

    private final Consumer<String> sink;
    private final StringBuilder pending = new StringBuilder();

    LineWriter(Consumer<String> sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public void write(char[] buffer, int offset, int length) {
        for (int index = offset; index < offset + length; index += 1) {
            char next = buffer[index];
            if (next == '\n') {
                emit();
            } else {
                pending.append(next);
            }
        }
    }

    private void emit() {
        int end = pending.length();
        if (end > 0 && pending.charAt(end - 1) == '\r') {
            end -= 1;
        }
        sink.accept(pending.substring(0, end));
        pending.setLength(0);
    }

    @Override
    public void flush() {
        // Lines are handed over as they end; a partial line waits for its ending or for close.
    }

    @Override
    public void close() {
        if (!pending.isEmpty()) {
            emit();
        }
    }
}
