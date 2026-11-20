package io.github.rkaushikdev.hitchline.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/** A configuration file that is there but could not be read. */
public class ConfigUnreadableException extends UncheckedIOException {

    public ConfigUnreadableException(Path path, IOException cause) {
        super("could not read the configuration file at " + path, cause);
    }
}
