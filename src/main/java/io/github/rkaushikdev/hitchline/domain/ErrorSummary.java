package io.github.rkaushikdev.hitchline.domain;

import java.util.List;

/**
 * The flat, log friendly shape of a domain failure. It never carries a stack trace.
 *
 * @param code the failure's wire code
 * @param message the failure's message
 * @param details the fields that were wrong
 */
public record ErrorSummary(String code, String message, List<ErrorDetail> details) {

    public ErrorSummary {
        details = List.copyOf(details);
    }
}
