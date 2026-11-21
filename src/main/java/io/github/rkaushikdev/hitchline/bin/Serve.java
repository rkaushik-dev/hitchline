package io.github.rkaushikdev.hitchline.bin;

import io.github.rkaushikdev.hitchline.Server;

/**
 * The {@code hitchline-serve} command: the api process. It reads the settings from the environment
 * and serves until it is told to stop.
 */
public final class Serve {

    private Serve() {}

    public static void main(String[] args) {
        Server.main(args);
    }
}
