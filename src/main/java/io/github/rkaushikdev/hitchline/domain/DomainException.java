package io.github.rkaushikdev.hitchline.domain;

import java.util.List;
import java.util.Objects;

/**
 * A domain level failure. Nothing in the domain throws a bare runtime exception, because the
 * http and cli layers both map failures back to the caller by reading {@link #code()} rather
 * than by matching on a message.
 */
public class DomainException extends RuntimeException {

    private final ErrorCode code;
    private final List<ErrorDetail> details;

    public DomainException(ErrorCode code, String message) {
        this(code, message, List.of(), null);
    }

    public DomainException(ErrorCode code, String message, List<ErrorDetail> details) {
        this(code, message, details, null);
    }

    public DomainException(ErrorCode code, String message, Throwable cause) {
        this(code, message, List.of(), cause);
    }

    public DomainException(ErrorCode code, String message, List<ErrorDetail> details, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.details = List.copyOf(details);
    }

    public ErrorCode code() {
        return code;
    }

    public List<ErrorDetail> details() {
        return details;
    }

    /** The name of the concrete failure, so logs say which kind it was. */
    public String name() {
        return getClass().getSimpleName();
    }

    /** The flat shape of the failure, without the stack. */
    public ErrorSummary toSummary() {
        return new ErrorSummary(code.wire(), getMessage(), details);
    }

    /** True for every domain failure and for nothing else. */
    public static boolean isDomainException(Object value) {
        return value instanceof DomainException;
    }
}
