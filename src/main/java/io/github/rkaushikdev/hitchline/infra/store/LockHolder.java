package io.github.rkaushikdev.hitchline.infra.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A writer lock this process holds.
 *
 * @param path the lock file
 * @param heldBy the owner it names
 */
public record LockHolder(Path path, LockOwner heldBy) {

    /** Gives the lock up by removing its file. */
    public void release() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            throw new UncheckedIOException("could not release the lock at " + path, failure);
        }
    }
}
