package io.github.rkaushikdev.hitchline.cli;

import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.config.Config;
import java.util.Objects;

/**
 * The context a terminal session runs in: output to standard output, failures to standard error,
 * each line ended with a line feed whatever the platform.
 */
record ConsoleContext(Ports ports, Config config) implements Context {

    ConsoleContext {
        Objects.requireNonNull(ports, "ports");
        Objects.requireNonNull(config, "config");
    }

    @Override
    public void out(String line) {
        System.out.print(line + "\n");
    }

    @Override
    public void fail(String line) {
        System.err.print(line + "\n");
    }
}
