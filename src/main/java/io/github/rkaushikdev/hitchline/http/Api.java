package io.github.rkaushikdev.hitchline.http;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.rkaushikdev.hitchline.app.AuditLog;
import io.github.rkaushikdev.hitchline.app.EventBus;
import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.domain.IdKind;
import io.github.rkaushikdev.hitchline.http.Metrics.Counter;
import io.github.rkaushikdev.hitchline.http.Metrics.Histogram;
import io.github.rkaushikdev.hitchline.http.middleware.Hardening;
import io.github.rkaushikdev.hitchline.http.middleware.RateLimit;
import io.github.rkaushikdev.hitchline.http.middleware.RequestContext;
import io.github.rkaushikdev.hitchline.http.routes.AuditRoutes;
import io.github.rkaushikdev.hitchline.http.routes.Routes;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The whole api: a server, the security headers, a request id, the rate limit, the token check and
 * every route, in that order. Tests build one of these against a memory store and inject into it.
 */
public final class Api {

    /** The version of the api this build serves. */
    public static final String VERSION = "1.0.0";

    private Api() {}

    /**
     * How to build the api.
     *
     * @param config the configuration it runs with
     * @param ports the store and clock it serves from
     * @param writesPerMinute writes per minute allowed to one caller; the configuration's when absent
     * @param allowedOrigins origins a browser may call the api from
     * @param audit an audit log to write into; a new one when absent
     * @param burst writes one caller may make at once; the configuration's when absent
     */
    public record ApiOptions(
            Config config,
            Ports ports,
            OptionalInt writesPerMinute,
            List<String> allowedOrigins,
            Optional<AuditLog> audit,
            OptionalInt burst) {

        public ApiOptions {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(ports, "ports");
            Objects.requireNonNull(writesPerMinute, "writesPerMinute");
            allowedOrigins = List.copyOf(allowedOrigins);
            Objects.requireNonNull(audit, "audit");
            Objects.requireNonNull(burst, "burst");
        }

        /** The api for {@code config} over {@code ports}, with nothing else said. */
        public static ApiOptions of(Config config, Ports ports) {
            return new ApiOptions(config, ports, OptionalInt.empty(), List.of(), Optional.empty(), OptionalInt.empty());
        }

        public ApiOptions withWritesPerMinute(int value) {
            return new ApiOptions(config, ports, OptionalInt.of(value), allowedOrigins, audit, burst);
        }

        public ApiOptions withAllowedOrigins(List<String> value) {
            return new ApiOptions(config, ports, writesPerMinute, value, audit, burst);
        }

        public ApiOptions withAudit(AuditLog value) {
            return new ApiOptions(config, ports, writesPerMinute, allowedOrigins, Optional.of(value), burst);
        }

        public ApiOptions withBurst(int value) {
            return new ApiOptions(config, ports, writesPerMinute, allowedOrigins, audit, OptionalInt.of(value));
        }
    }

    /**
     * The api with the parts a caller may want to read back.
     *
     * @param app the application
     * @param metrics the counters it keeps
     * @param audit the audit log it writes
     * @param ports the ports its routes use, carrying the bus the audit log hears
     */
    public record BuiltApi(HttpApp app, Metrics metrics, AuditLog audit, Ports ports) {}

    /** The routes that describe the service itself: its counters, its description and its readiness. */
    @RestController
    public static final class ServiceRoutes {

        private final Metrics metrics;
        private final Ports ports;
        private final Config config;

        ServiceRoutes(Metrics metrics, Ports ports, Config config) {
            this.metrics = metrics;
            this.ports = ports;
            this.config = config;
        }

        @GetMapping("/metrics")
        public ResponseEntity<String> metrics() {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/plain; version=0.0.4"))
                    .body(metrics.render());
        }

        @GetMapping("/openapi.json")
        public ObjectNode openApi() {
            return Manifest.openApiDocument(VERSION);
        }

        @GetMapping("/ready")
        public ResponseEntity<Readiness> ready() {
            Readiness report = Readiness.read(ports, config);
            return ResponseEntity.status(Readiness.statusFor(report)).body(report);
        }
    }

    /** The whole api for {@code config} over {@code ports}. */
    public static HttpApp build(Config config, Ports ports) {
        return build(ApiOptions.of(config, ports));
    }

    /** The whole api. */
    public static HttpApp build(ApiOptions options) {
        return buildWithMetrics(options).app();
    }

    /**
     * The whole api with its metrics and audit log. Everything the services announce is audited,
     * and the ports handed to the routes are the ones carrying that bus, so a route cannot write
     * without the trail seeing it.
     */
    public static BuiltApi buildWithMetrics(ApiOptions options) {
        Config config = options.config();
        EventBus bus = options.ports().events().orElseGet(EventBus::create);
        AuditLog audit = options.audit().orElseGet(AuditLog::create);
        AuditLog.auditEverything(bus, audit);
        Ports ports = options.ports().withEvents(bus);
        HttpApp app = HttpServer.build(config, ports);
        Metrics metrics = Metrics.create();
        Counter served = metrics.counter("hitchline_requests_total", "requests served by status");
        Histogram latency = metrics.histogram(
                "hitchline_request_ms", "how long a request took in milliseconds", Metrics.LATENCY_BOUNDS);

        Hardening.register(app, options.allowedOrigins());
        RequestContext.register(app, () -> ports.ids().next(IdKind.CHECK_IN).replace("chk_", "req-"));
        RateLimit.register(
                app,
                ports.clock(),
                options.burst().orElse(config.burst()),
                options.writesPerMinute().orElse(config.writesPerMinute()));
        Auth.register(app, config.tokens());

        app.addResponseHook((request, response, elapsedMs) -> {
            served.add(1, Map.of("method", request.getMethod(), "status", String.valueOf(response.getStatus())));
            latency.observe(elapsedMs, Map.of("method", request.getMethod()));
        });

        // Readiness reads the store as it was handed in, so a store on disk still counts as durable
        // once its ports carry the bus.
        app.addRoutes(new ServiceRoutes(metrics, options.ports(), config));
        Routes.registerAll(app, ports);
        AuditRoutes.register(app, audit);
        return new BuiltApi(app, metrics, audit, ports);
    }
}
