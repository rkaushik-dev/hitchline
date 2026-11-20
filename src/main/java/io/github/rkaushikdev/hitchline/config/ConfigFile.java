package io.github.rkaushikdev.hitchline.config;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Json;
import io.github.rkaushikdev.hitchline.util.MalformedJsonException;
import io.github.rkaushikdev.hitchline.util.Numbers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A configuration file, for the deployments where a dozen environment variables in a unit file is
 * worse than one JSON file somebody can read. The environment still wins, because that is where a
 * secret comes from and a container orchestrator has no other way to reach in.
 *
 * @param settings the settings the file named, in the order they are listed here, as written
 */
public record ConfigFile(Map<String, JsonNode> settings) {

    private static final List<String> KEYS = List.of(
            "store",
            "storePath",
            "host",
            "port",
            "logLevel",
            "defaultZone",
            "allowedOrigins",
            "writesPerMinute",
            "burst");

    private static final Map<String, String> TO_ENV = orderedEnv();

    private static Map<String, String> orderedEnv() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("store", "HITCHLINE_STORE");
        names.put("storePath", "HITCHLINE_STORE_PATH");
        names.put("host", "HITCHLINE_HOST");
        names.put("port", "HITCHLINE_PORT");
        names.put("logLevel", "HITCHLINE_LOG_LEVEL");
        names.put("defaultZone", "HITCHLINE_DEFAULT_ZONE");
        names.put("writesPerMinute", "HITCHLINE_WRITES_PER_MINUTE");
        names.put("burst", "HITCHLINE_BURST");
        return Collections.unmodifiableMap(names);
    }

    public ConfigFile {
        settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }

    /**
     * Reads a configuration file's text. It must be one JSON object naming only known settings;
     * the port and the write limits must be numbers and the origins a list.
     */
    public static ConfigFile parse(String text) {
        JsonNode parsed;
        try {
            parsed = Json.parse(text);
        } catch (MalformedJsonException notJson) {
            throw ValidationException.field("config", "is not json");
        }
        if (!parsed.isObject()) {
            throw ValidationException.field("config", "must be a json object");
        }
        for (Map.Entry<String, JsonNode> field : parsed.properties()) {
            String key = field.getKey();
            if (!KEYS.contains(key)) {
                throw ValidationException.field("config", "does not know the setting " + key);
            }
        }
        Map<String, JsonNode> file = new LinkedHashMap<>();
        for (String key : KEYS) {
            JsonNode value = parsed.get(key);
            if (value == null) {
                continue;
            }
            boolean numeric = key.equals("port") || key.equals("writesPerMinute") || key.equals("burst");
            if (numeric && !value.isNumber()) {
                throw ValidationException.field(key, "must be a number in the config file");
            }
            if (key.equals("allowedOrigins") && !value.isArray()) {
                throw ValidationException.field("allowedOrigins", "must be a list in the config file");
            }
            file.put(key, value);
        }
        return new ConfigFile(file);
    }

    /** The settings as plain values: text, numbers, flags and lists. */
    public Map<String, Object> values() {
        Map<String, Object> plain = new LinkedHashMap<>();
        settings.forEach((key, value) -> plain.put(key, Json.mapper().convertValue(value, Object.class)));
        return plain;
    }

    /** The browser origins the file allows, none when it names none. */
    public List<String> allowedOrigins() {
        JsonNode origins = settings.get("allowedOrigins");
        List<String> listed = new ArrayList<>();
        if (origins != null) {
            origins.forEach(origin -> listed.add(origin.asText()));
        }
        return List.copyOf(listed);
    }

    /**
     * The settings as the environment variables that would set them, so one reader validates
     * both. Only text and numbers carry over; the origins are not an environment setting.
     */
    public Map<String, String> asEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        TO_ENV.forEach((key, name) -> {
            JsonNode value = settings.get(key);
            if (value == null) {
                return;
            }
            if (value.isTextual()) {
                env.put(name, value.textValue());
            } else if (value.isNumber()) {
                env.put(name, Numbers.format(value.doubleValue()));
            }
        });
        return env;
    }

    /** The environment alone, with no file. */
    public static LoadedConfig load(Map<String, String> env) {
        return new LoadedConfig(Config.read(env), List.of(), Optional.empty());
    }

    /**
     * Reads the file at {@code path}, lets the environment overwrite anything it names, and hands
     * back one settled configuration.
     */
    public static LoadedConfig load(Path path, Map<String, String> env) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException missing) {
            throw ValidationException.field("config", "no configuration file at " + path);
        } catch (IOException unreadable) {
            throw new ConfigUnreadableException(path, unreadable);
        }
        ConfigFile file = parse(text);
        Map<String, String> merged = new HashMap<>(file.asEnvironment());
        merged.putAll(env);
        return new LoadedConfig(Config.read(merged), file.allowedOrigins(), Optional.of(path.toString()));
    }

    /** Reads the file {@code HITCHLINE_CONFIG} names, if it names one, under this process's environment. */
    public static LoadedConfig loadFromEnvironment() {
        Map<String, String> env = System.getenv();
        String named = env.get("HITCHLINE_CONFIG");
        return named == null ? load(env) : load(Path.of(named), env);
    }
}
