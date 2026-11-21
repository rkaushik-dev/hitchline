package io.github.rkaushikdev.hitchline.http;

import io.github.rkaushikdev.hitchline.app.Durable;
import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.WireValue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Whether the process can serve a request right now. Liveness asks whether the process is worth
 * keeping; readiness asks whether it can serve a request right now. A container that conflates
 * them gets restarted every time the disk is slow.
 *
 * @param state the worst state of any check
 * @param checks each check, in the order it ran
 */
public record Readiness(CheckState state, List<CheckResult> checks) {

    /** A store read slower than this, in milliseconds, is reported as a warning. */
    public static final double DEFAULT_SLOW_MS = 250;

    public Readiness {
        Objects.requireNonNull(state, "state");
        checks = List.copyOf(checks);
    }

    /** How one check went. */
    public enum CheckState implements WireValue {
        PASS("pass"),
        WARN("warn"),
        FAIL("fail");

        private final String wire;

        CheckState(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }

        @Override
        public String toString() {
            return wire;
        }
    }

    /**
     * One check.
     *
     * @param name what was checked
     * @param state how it went
     * @param detail what it found, or why it failed
     * @param ms how long it took, to the nearest millisecond
     */
    public record CheckResult(String name, CheckState state, String detail, long ms) {

        public CheckResult {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(detail, "detail");
        }

        CheckResult withState(CheckState value) {
            return new CheckResult(name, value, detail, ms);
        }
    }

    /** The worst of {@code checks}: any failure fails, any warning warns, and nothing passes. */
    public static CheckState worstOf(List<CheckResult> checks) {
        if (checks.stream().anyMatch(check -> check.state() == CheckState.FAIL)) {
            return CheckState.FAIL;
        }
        if (checks.stream().anyMatch(check -> check.state() == CheckState.WARN)) {
            return CheckState.WARN;
        }
        return CheckState.PASS;
    }

    /** 503 when something failed, 200 otherwise: a warning still serves. */
    public static int statusFor(Readiness readiness) {
        return readiness.state() == CheckState.FAIL ? 503 : 200;
    }

    /** Reads readiness on the process's own monotonic clock. */
    public static Readiness read(Ports ports, Config config) {
        return read(ports, config, () -> System.nanoTime() / 1_000_000.0, DEFAULT_SLOW_MS);
    }

    /**
     * Reads readiness.
     *
     * @param ports the store and clock to check
     * @param config the configuration, which says whether the store should be on disk
     * @param now a monotonic time in milliseconds, for timing each check
     * @param slowMs a store read slower than this is reported as a warning
     */
    public static Readiness read(Ports ports, Config config, DoubleSupplier now, double slowMs) {
        List<CheckResult> checks = new ArrayList<>();

        CheckResult store = timed("store", now, () -> ports.members().all().size() + " members");
        checks.add(store.state() == CheckState.PASS && store.ms() > slowMs ? store.withState(CheckState.WARN) : store);

        checks.add(timed("clock", now, () -> {
            Instant reading = ports.clock().now();
            if (reading == null) {
                throw new IllegalStateException("the clock read nothing");
            }
            return Zones.formatInstant(reading);
        }));

        boolean onDisk = config.store() == StoreKind.FILE;
        checks.add(new CheckResult(
                "durability",
                onDisk && !Durable.isDurable(ports) ? CheckState.WARN : CheckState.PASS,
                onDisk ? "journal on disk" : "memory only",
                0));

        return new Readiness(worstOf(checks), checks);
    }

    private static CheckResult timed(String name, DoubleSupplier now, Supplier<String> body) {
        double started = now.getAsDouble();
        try {
            String detail = body.get();
            return new CheckResult(name, CheckState.PASS, detail, Math.round(now.getAsDouble() - started));
        } catch (RuntimeException failed) {
            String detail = failed.getMessage() == null ? failed.toString() : failed.getMessage();
            return new CheckResult(name, CheckState.FAIL, detail, Math.round(now.getAsDouble() - started));
        }
    }
}
