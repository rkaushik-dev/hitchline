package io.github.rkaushikdev.hitchline.config;

import io.github.rkaushikdev.hitchline.util.WireValue;
import java.util.Optional;

/** How much the service logs, from everything to nothing at all. */
public enum LogLevel implements WireValue {
    TRACE("trace"),
    DEBUG("debug"),
    INFO("info"),
    WARN("warn"),
    ERROR("error"),
    SILENT("silent");

    private final String wire;

    LogLevel(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }

    @Override
    public String toString() {
        return wire;
    }

    public static Optional<LogLevel> fromWire(String wire) {
        return WireValue.fromWire(LogLevel.class, wire);
    }
}
