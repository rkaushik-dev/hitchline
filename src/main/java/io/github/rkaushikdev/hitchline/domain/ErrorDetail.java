package io.github.rkaushikdev.hitchline.domain;

import java.util.Objects;

/**
 * One problem with one field of a payload.
 *
 * @param path dotted path to the offending field, empty for a failure of the whole object
 * @param message what is wrong with it
 */
public record ErrorDetail(String path, String message) {

    public ErrorDetail {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
    }
}
