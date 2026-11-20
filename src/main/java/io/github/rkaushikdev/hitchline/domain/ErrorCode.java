package io.github.rkaushikdev.hitchline.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The kinds of domain failure. The http and cli layers map a failure back to the caller by its
 * code rather than by matching on a message, so the wire text of each code is part of the
 * contract.
 */
public enum ErrorCode {
    VALIDATION_FAILED("validation_failed"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    FORBIDDEN("forbidden"),
    UNSUPPORTED("unsupported");

    private final String wire;

    ErrorCode(String wire) {
        this.wire = wire;
    }

    /** The code as it is written in responses, logs and cli output. */
    public String wire() {
        return wire;
    }

    /** The code written as {@code wire}, if there is one. */
    public static Optional<ErrorCode> fromWire(String wire) {
        return Arrays.stream(values()).filter(code -> code.wire.equals(wire)).findFirst();
    }

    @Override
    public String toString() {
        return wire;
    }
}
