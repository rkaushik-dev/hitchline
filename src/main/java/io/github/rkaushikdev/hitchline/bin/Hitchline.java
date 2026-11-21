package io.github.rkaushikdev.hitchline.bin;

import io.github.rkaushikdev.hitchline.cli.Main;
import io.github.rkaushikdev.hitchline.config.Config;

/**
 * The {@code hitchline} command. It reads the settings from the environment, runs one command line
 * against the configured store and exits with the command's code.
 */
public final class Hitchline {

    private Hitchline() {}

    public static void main(String[] args) {
        System.exit(Main.runConfigured(Config.fromEnvironment(), args));
    }
}
