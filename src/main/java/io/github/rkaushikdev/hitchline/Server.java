package io.github.rkaushikdev.hitchline;

import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.ConfigFile;
import io.github.rkaushikdev.hitchline.config.LoadedConfig;
import io.github.rkaushikdev.hitchline.http.Api;
import io.github.rkaushikdev.hitchline.http.Api.ApiOptions;
import io.github.rkaushikdev.hitchline.http.ApiApplication;
import io.github.rkaushikdev.hitchline.infra.Shutdown;
import io.github.rkaushikdev.hitchline.infra.SignalTarget;
import io.github.rkaushikdev.hitchline.infra.store.Stores;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The api process. It reads the environment and the configuration file it names, opens the store
 * the configuration names, serves the api, and closes both on a stop signal.
 */
public final class Server {

    private static final Logger LOG = LoggerFactory.getLogger(Server.class);

    private Server() {}

    /**
     * A running api.
     *
     * @param context the server serving it
     * @param store the store it serves from
     * @param shutdown closes the server, then the store
     */
    public record Running(ConfigurableApplicationContext context, Ports store, Shutdown shutdown) {

        /** The port it is listening on. */
        public int port() {
            return ApiApplication.portOf(context);
        }
    }

    /** Serves under this process's environment until a stop signal. */
    public static void main(String[] args) {
        serve(ConfigFile.loadFromEnvironment(), SignalTarget.jvm(), code -> Runtime.getRuntime()
                .halt(code));
    }

    /**
     * Opens the store, starts serving, and wires both stop signals to a shutdown that closes the
     * server and then the store.
     *
     * @param signals where the stop signals come from
     * @param exit called with 0 once a shutdown went cleanly, 1 when part of it failed
     */
    public static Running serve(LoadedConfig loaded, SignalTarget signals, IntConsumer exit) {
        Config config = loaded.config();
        Ports store = Stores.openStore(config);
        ConfigurableApplicationContext context;
        try {
            context = ApiApplication.serve(
                    Api.build(ApiOptions.of(config, store).withAllowedOrigins(loaded.allowedOrigins())), config);
        } catch (RuntimeException failed) {
            Stores.closeStore(store);
            throw failed;
        }

        Shutdown shutdown = Shutdown.create();
        shutdown.add("http", context::close);
        shutdown.add("store", () -> Stores.closeStore(store));
        Shutdown.onSignals(signals, shutdown, report -> {
            if (!report.failed().isEmpty()) {
                LOG.atError().addKeyValue("failed", report.failed()).log("shutdown had trouble");
            }
            exit.accept(report.failed().isEmpty() ? 0 : 1);
        });

        Running running = new Running(context, store, shutdown);
        LOG.atInfo()
                .addKeyValue("store", config.store().wire())
                .addKeyValue("path", config.storePath())
                .addKeyValue("config", loaded.from().orElse(""))
                .addKeyValue("port", running.port())
                .log("hitchline is up");
        return running;
    }
}
