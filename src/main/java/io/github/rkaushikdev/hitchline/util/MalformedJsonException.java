package io.github.rkaushikdev.hitchline.util;

/** Text that was meant to be one JSON document and is not. */
public class MalformedJsonException extends RuntimeException {

    public MalformedJsonException(String message) {
        super(message);
    }
}
