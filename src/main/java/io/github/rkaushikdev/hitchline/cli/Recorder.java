package io.github.rkaushikdev.hitchline.cli;

import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.config.Config;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A context that keeps every line a command printed, output and failures apart, so a test can
 * assert on the text rather than on a stream.
 */
public final class Recorder implements Context {

    private final Ports ports;
    private final Config config;
    private final List<String> lines = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    Recorder(Ports ports, Config config) {
        this.ports = Objects.requireNonNull(ports, "ports");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public Ports ports() {
        return ports;
    }

    @Override
    public Config config() {
        return config;
    }

    @Override
    public void out(String line) {
        lines.add(line);
    }

    @Override
    public void fail(String line) {
        errors.add(line);
    }

    /** The output lines so far, in order; the list follows later output. */
    public List<String> lines() {
        return Collections.unmodifiableList(lines);
    }

    /** The failure lines so far, in order; the list follows later failures. */
    public List<String> errors() {
        return Collections.unmodifiableList(errors);
    }

    /** The output joined with line feeds. */
    public String text() {
        return String.join("\n", lines);
    }

    /** The failures joined with line feeds. */
    public String errorText() {
        return String.join("\n", errors);
    }

    /** Forgets everything printed so far. */
    public void clear() {
        lines.clear();
        errors.clear();
    }
}
