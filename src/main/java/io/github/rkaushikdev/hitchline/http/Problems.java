package io.github.rkaushikdev.hitchline.http;

import io.github.rkaushikdev.hitchline.domain.DomainException;
import io.github.rkaushikdev.hitchline.domain.ErrorCode;
import io.github.rkaushikdev.hitchline.domain.ErrorDetail;
import java.util.List;
import java.util.Objects;

/**
 * The error body every route answers with, and the status each failure maps to. The body is
 * deliberately flat: the crew app reads {@code code} and shows {@code message}, and the field list
 * is there for a form that wants to mark the box that was wrong.
 */
public final class Problems {

    /** The code written for a failure nobody expected. */
    public static final String INTERNAL_ERROR = "internal_error";

    /**
     * What a failed request answers with.
     *
     * @param code a domain error code, or {@code internal_error}
     * @param message the sentence to show
     * @param details the fields that were wrong
     */
    public record ProblemBody(String code, String message, List<ErrorDetail> details) {

        public ProblemBody {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            details = List.copyOf(details);
        }
    }

    private Problems() {}

    /** The status a failure answers with: its code's status, or 500 for anything else. */
    public static int statusFor(Object error) {
        if (error instanceof DomainException domain) {
            return statusOf(domain.code());
        }
        return 500;
    }

    /** The status every failure carrying {@code code} answers with. */
    public static int statusOf(ErrorCode code) {
        return switch (code) {
            case VALIDATION_FAILED -> 400;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case FORBIDDEN -> 403;
            case UNSUPPORTED -> 422;
        };
    }

    /** The body a failure answers with; a failure nobody expected says nothing about itself. */
    public static ProblemBody problemFor(Object error) {
        if (error instanceof DomainException domain) {
            return new ProblemBody(domain.code().wire(), domain.getMessage(), domain.details());
        }
        return new ProblemBody(INTERNAL_ERROR, "something went wrong on our side", List.of());
    }

    /** True when the failure is the caller's fault and should not page anybody. */
    public static boolean isCallerFault(Object error) {
        return error instanceof DomainException domain && domain.code() != ErrorCode.UNSUPPORTED;
    }

    /** The failure as a domain failure: itself when it is one, otherwise an unsupported one. */
    public static DomainException asDomainError(Throwable error) {
        if (error instanceof DomainException domain) {
            return domain;
        }
        String message = error.getMessage() == null ? error.toString() : error.getMessage();
        return new DomainException(ErrorCode.UNSUPPORTED, message, error);
    }
}
