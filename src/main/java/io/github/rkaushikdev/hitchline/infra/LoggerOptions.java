package io.github.rkaushikdev.hitchline.infra;

import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.LogLevel;
import io.github.rkaushikdev.hitchline.util.Numbers;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * How the service logs. The important part is the redaction list: a check in note can hold
 * anything a crew member typed, and a bearer token must never reach a log line, so both are
 * stripped before anything is written.
 *
 * @param level the least severe level written
 * @param redact the request and response fields never written
 * @param base fields every line carries
 */
public record LoggerOptions(LogLevel level, List<String> redact, Map<String, String> base) {

    /** Fields that never reach a log line. */
    public static final List<String> REDACTED_PATHS = List.of(
            "req.headers.authorization",
            "req.headers.cookie",
            "body.note",
            "body.idempotencyKey",
            "res.headers[\"set-cookie\"]");

    private static final List<LogLevel> ORDER = List.of(LogLevel.values());

    public LoggerOptions {
        redact = List.copyOf(redact);
        base = Collections.unmodifiableMap(new LinkedHashMap<>(base));
    }

    public static LoggerOptions of(Config config) {
        return of(config, "hitchline");
    }

    /** Options naming {@code service} and the store kind on every line. */
    public static LoggerOptions of(Config config, String service) {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("service", service);
        base.put("store", config.store().wire());
        return new LoggerOptions(config.logLevel(), REDACTED_PATHS, base);
    }

    /** The options for a configuration, or nothing when it asks for silence. */
    public static Optional<LoggerOptions> forConfig(Config config) {
        return config.logLevel() == LogLevel.SILENT ? Optional.empty() : Optional.of(of(config));
    }

    /** True when a line at {@code level} gets through a {@code threshold}; nothing is silent's. */
    public static boolean isEnabled(LogLevel level, LogLevel threshold) {
        if (level == LogLevel.SILENT) {
            return false;
        }
        return ORDER.indexOf(level) >= ORDER.indexOf(threshold);
    }

    /**
     * A one line summary of a request, used where a full log line would be noise. Query strings are
     * dropped because a window is not worth a line and an id in a path is enough to find the
     * request again.
     */
    public static String requestLine(String method, String url, int status, double ms) {
        String path = Texts.split(url, "?").get(0);
        return method.toUpperCase(Locale.ROOT) + " " + path + " " + status + " " + Numbers.round(ms) + "ms";
    }
}
