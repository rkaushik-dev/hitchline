package io.github.rkaushikdev.hitchline.config;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Numbers;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Configuration, read once at startup from {@code HITCHLINE_*} environment variables. Everything
 * is optional and has a default that works on a laptop, because the first thing anybody does with
 * this is run it without reading the readme.
 *
 * @param store where records are kept
 * @param writesPerMinute writes a single caller may make in a minute before being held back
 * @param burst writes a caller may make in one burst before the refill matters
 * @param storePath the journal file
 * @param host the address the api listens on
 * @param port the port it listens on
 * @param logLevel how much it logs
 * @param defaultZone the zone used where nobody's own zone applies
 * @param tokens the bearer tokens the api accepts
 */
public record Config(
        StoreKind store,
        int writesPerMinute,
        int burst,
        String storePath,
        String host,
        int port,
        LogLevel logLevel,
        String defaultZone,
        List<ApiToken> tokens) {

    public static final StoreKind DEFAULT_STORE = StoreKind.FILE;
    public static final String DEFAULT_STORE_PATH = ".hitchline-data/hitchline.jsonl";
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8_417;
    public static final LogLevel DEFAULT_LOG_LEVEL = LogLevel.INFO;
    public static final String DEFAULT_ZONE = "UTC";
    public static final int DEFAULT_WRITES_PER_MINUTE = 120;
    public static final int DEFAULT_BURST = 30;

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{16,128}");

    public Config {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(storePath, "storePath");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(logLevel, "logLevel");
        Objects.requireNonNull(defaultZone, "defaultZone");
        tokens = List.copyOf(tokens);
    }

    /** The configuration this process's environment describes. */
    public static Config fromEnvironment() {
        return read(System.getenv());
    }

    /** Reads {@code env}, where a missing or blank value takes its default. */
    public static Config read(Map<String, String> env) {
        String zone = env.getOrDefault("HITCHLINE_DEFAULT_ZONE", DEFAULT_ZONE);
        if (!Zones.isKnownZone(zone)) {
            throw ValidationException.field("HITCHLINE_DEFAULT_ZONE", "is not a timezone: " + zone);
        }
        String storePath = env.getOrDefault("HITCHLINE_STORE_PATH", DEFAULT_STORE_PATH);
        if (Texts.trim(storePath).isEmpty()) {
            throw ValidationException.field("HITCHLINE_STORE_PATH", "must not be empty");
        }
        StoreKind store = readStore(env.get("HITCHLINE_STORE"));
        String host = env.getOrDefault("HITCHLINE_HOST", DEFAULT_HOST);
        int port = readPort(env.get("HITCHLINE_PORT"));
        LogLevel logLevel = readLogLevel(env.get("HITCHLINE_LOG_LEVEL"));
        int writesPerMinute = readCount(
                env.get("HITCHLINE_WRITES_PER_MINUTE"), "HITCHLINE_WRITES_PER_MINUTE", DEFAULT_WRITES_PER_MINUTE);
        int burst = readCount(env.get("HITCHLINE_BURST"), "HITCHLINE_BURST", DEFAULT_BURST);
        List<ApiToken> tokens = parseTokens(env.get("HITCHLINE_TOKENS"));
        return new Config(store, writesPerMinute, burst, storePath, host, port, logLevel, zone, tokens);
    }

    private static boolean isBlank(String raw) {
        return raw == null || Texts.trim(raw).isEmpty();
    }

    private static int readCount(String raw, String name, int fallback) {
        if (isBlank(raw)) {
            return fallback;
        }
        double value = Numbers.parse(raw);
        if (!Numbers.isInteger(value) || value < 1 || value > 100_000) {
            throw ValidationException.field(name, "must be a whole number from 1 to 100000, got " + raw);
        }
        return (int) value;
    }

    private static int readPort(String raw) {
        if (isBlank(raw)) {
            return DEFAULT_PORT;
        }
        double port = Numbers.parse(raw);
        if (!Numbers.isInteger(port) || port < 1 || port > 65_535) {
            throw ValidationException.field("HITCHLINE_PORT", "must be a port number, got " + raw);
        }
        return (int) port;
    }

    private static LogLevel readLogLevel(String raw) {
        if (isBlank(raw)) {
            return DEFAULT_LOG_LEVEL;
        }
        return LogLevel.fromWire(Texts.trim(raw).toLowerCase(Locale.ROOT))
                .orElseThrow(() -> ValidationException.field("HITCHLINE_LOG_LEVEL", "is not a level: " + raw));
    }

    private static StoreKind readStore(String raw) {
        if (isBlank(raw)) {
            return DEFAULT_STORE;
        }
        return StoreKind.fromWire(Texts.trim(raw).toLowerCase(Locale.ROOT))
                .orElseThrow(() -> ValidationException.field("HITCHLINE_STORE", "must be memory or file, got " + raw));
    }

    /**
     * Tokens arrive as {@code token:memberId:scope|scope}, separated by commas, the scope defaulting
     * to {@code read}. It is a blunt format and it is meant to be: the real deployment puts a proxy
     * in front, and this exists so a crew can run the thing on a laptop.
     */
    public static List<ApiToken> parseTokens(String raw) {
        if (isBlank(raw)) {
            return List.of();
        }
        List<ApiToken> tokens = new ArrayList<>();
        for (String part : Texts.split(raw, ",")) {
            String entry = Texts.trim(part);
            if (entry.isEmpty()) {
                continue;
            }
            List<String> pieces = Texts.split(entry, ":");
            if (pieces.size() < 2) {
                throw ValidationException.field("HITCHLINE_TOKENS", "is not token:member:scopes, got " + entry);
            }
            String token = pieces.get(0);
            if (!TOKEN.matcher(token).matches()) {
                throw ValidationException.field("HITCHLINE_TOKENS", "has a token that is too short or odd");
            }
            String scopeText = pieces.size() > 2 ? pieces.get(2) : "read";
            List<String> scopes = Texts.split(scopeText, "|").stream()
                    .map(Texts::trim)
                    .filter(scope -> !scope.isEmpty())
                    .toList();
            tokens.add(new ApiToken(token, pieces.get(1), scopes));
        }
        return tokens;
    }

    /** One line, such as {@code 127.0.0.1:8417 on .hitchline-data/hitchline.jsonl, 0 tokens}. */
    public String describe() {
        String where = store == StoreKind.FILE ? storePath : "memory";
        return host + ":" + port + " on " + where + ", " + tokens.size() + " tokens";
    }

    public Config withStore(StoreKind value) {
        return new Config(value, writesPerMinute, burst, storePath, host, port, logLevel, defaultZone, tokens);
    }

    public Config withStorePath(String value) {
        return new Config(store, writesPerMinute, burst, value, host, port, logLevel, defaultZone, tokens);
    }

    public Config withLogLevel(LogLevel value) {
        return new Config(store, writesPerMinute, burst, storePath, host, port, value, defaultZone, tokens);
    }

    public Config withTokens(List<ApiToken> value) {
        return new Config(store, writesPerMinute, burst, storePath, host, port, logLevel, defaultZone, value);
    }

    public Config withPort(int value) {
        return new Config(store, writesPerMinute, burst, storePath, host, value, logLevel, defaultZone, tokens);
    }

    public Config withLimits(int newWritesPerMinute, int newBurst) {
        return new Config(store, newWritesPerMinute, newBurst, storePath, host, port, logLevel, defaultZone, tokens);
    }
}
