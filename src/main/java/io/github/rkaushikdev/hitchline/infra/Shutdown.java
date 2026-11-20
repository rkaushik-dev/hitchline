package io.github.rkaushikdev.hitchline.infra;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Shutting down without losing writes. Each task is run once, in the order it was registered, and
 * a task that throws does not stop the ones behind it, because closing the journal matters more
 * than any one of them succeeding.
 */
public final class Shutdown {

    private final List<String> names = new ArrayList<>();
    private final List<ShutdownTask> order = new ArrayList<>();
    private ShutdownReport report;
    private boolean started;

    public static Shutdown create() {
        return new Shutdown();
    }

    /** Registers a task to run on the way out. Nothing may be added once the shutdown has begun. */
    public synchronized void add(String name, ShutdownTask task) {
        if (Texts.trim(name).isEmpty()) {
            throw ValidationException.field("name", "must not be empty");
        }
        if (started) {
            throw ValidationException.field("task", name + " was added while shutting down");
        }
        names.add(name);
        order.add(task);
    }

    /** Runs every task the first time it is called; later calls answer the same report. */
    public synchronized ShutdownReport run() {
        if (report != null) {
            return report;
        }
        started = true;
        List<String> failed = new ArrayList<>();
        for (int index = 0; index < order.size(); index += 1) {
            try {
                order.get(index).run();
            } catch (Exception failure) {
                failed.add(names.get(index));
            }
        }
        report = new ShutdownReport(order.size(), failed);
        return report;
    }

    public synchronized boolean started() {
        return started;
    }

    public synchronized int size() {
        return order.size();
    }

    /** Wires both stop signals to one shutdown, handing its report on, and answers how many it wired. */
    public static int onSignals(SignalTarget target, Shutdown shutdown, Consumer<ShutdownReport> after) {
        SignalName[] signals = SignalName.values();
        for (SignalName signal : signals) {
            target.once(signal, () -> after.accept(shutdown.run()));
        }
        return signals.length;
    }
}
