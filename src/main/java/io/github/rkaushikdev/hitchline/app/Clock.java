package io.github.rkaushikdev.hitchline.app;

import java.time.Instant;

/**
 * Where the current time comes from. Nothing reads the system time directly, so every reading of
 * the current time comes through one of these and a test can pin it.
 */
@FunctionalInterface
public interface Clock {

    Instant now();
}
