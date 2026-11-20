package io.github.rkaushikdev.hitchline.infra.store;

import io.github.rkaushikdev.hitchline.app.Clock;
import io.github.rkaushikdev.hitchline.app.Durable;
import io.github.rkaushikdev.hitchline.app.IdSource;
import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import java.nio.file.Path;

/**
 * Opens whichever store the configuration asks for. Callers get the ports and, when the store has
 * anything to flush, close it with {@link #closeStore}.
 */
public final class Stores {

    private Stores() {}

    public static Ports openStore(Config config) {
        return openStore(config, Clocks.systemClock(), IdSources.randomIds(), true);
    }

    /** Opens the configured store; with {@code lock} false a file store shares the journal. */
    public static Ports openStore(Config config, Clock clock, IdSource ids, boolean lock) {
        if (config.store() == StoreKind.MEMORY) {
            return MemoryStore.create(clock, ids);
        }
        return FileStore.open(Path.of(config.storePath()), clock, ids, lock);
    }

    /**
     * Opens the store for a tool that only reads. It shares the journal with a running server
     * rather than refusing to start, which is what a status or an export command wants.
     */
    public static Ports openStoreForReading(Config config) {
        return openStore(config, Clocks.systemClock(), IdSources.randomIds(), false);
    }

    /** Flushes and closes a store that holds anything open; a memory store needs nothing. */
    public static void closeStore(Ports store) {
        if (store instanceof Durable durable) {
            durable.flush();
            durable.close();
        }
    }
}
