package io.github.rkaushikdev.hitchline.app;

/** A store that can be flushed and closed, which the journal backed one needs and memory does not. */
public interface Durable extends AutoCloseable {

    void flush();

    @Override
    void close();

    /** True for anything that can be flushed and closed. */
    static boolean isDurable(Object value) {
        return value instanceof Durable;
    }
}
