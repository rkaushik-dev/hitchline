package io.github.rkaushikdev.hitchline.config;

import java.util.List;
import java.util.Optional;

/**
 * A settled configuration and where it came from.
 *
 * @param config the configuration
 * @param allowedOrigins the browser origins the file allows
 * @param from the file it was read from, when there was one
 */
public record LoadedConfig(Config config, List<String> allowedOrigins, Optional<String> from) {

    public LoadedConfig {
        allowedOrigins = List.copyOf(allowedOrigins);
    }
}
