package io.github.rkaushikdev.hitchline.infra;

/** Something that can call a listener once when a signal arrives. */
@FunctionalInterface
public interface SignalTarget {

    void once(SignalName signal, Runnable listener);

    /**
     * This process. Both signals stop the JVM, which runs its shutdown hooks, so each listener is
     * registered as a hook and runs once whichever signal arrives.
     */
    static SignalTarget jvm() {
        return (signal, listener) -> Runtime.getRuntime().addShutdownHook(new Thread(listener, "hitchline-" + signal));
    }
}
