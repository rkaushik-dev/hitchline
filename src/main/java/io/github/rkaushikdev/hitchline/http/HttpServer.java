package io.github.rkaushikdev.hitchline.http;

import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.LogLevel;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The http surface. Routes are registered by the modules that own them; this only sets the
 * application up, maps errors and answers the health check.
 */
public final class HttpServer {

    private HttpServer() {}

    /**
     * What the health check answers.
     *
     * @param status always {@code ok}
     * @param at the clock reading
     * @param store which store the process runs on
     */
    public record HealthBody(String status, String at, String store) {

        public HealthBody {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(store, "store");
        }
    }

    /** The health route: liveness and the clock reading. */
    @RestController
    public static final class HealthRoute {

        private final Config config;
        private final Ports ports;

        HealthRoute(Config config, Ports ports) {
            this.config = config;
            this.ports = ports;
        }

        @GetMapping("/health")
        public HealthBody health() {
            return new HealthBody(
                    "ok",
                    Zones.formatInstant(ports.clock().now()),
                    config.store().wire());
        }
    }

    /** An application with the error mapping and the health route, ready for modules to register into. */
    public static HttpApp build(Config config, Ports ports) {
        HttpApp app = new HttpApp(new ProblemAdvice(config.logLevel() != LogLevel.SILENT));
        app.addRoutes(new HealthRoute(config, ports));
        return app;
    }
}
