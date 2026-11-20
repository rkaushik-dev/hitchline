package io.github.rkaushikdev.hitchline.infra;

/** One thing to do on the way out, such as closing the listener or flushing the journal. */
@FunctionalInterface
public interface ShutdownTask {

    void run() throws Exception;
}
