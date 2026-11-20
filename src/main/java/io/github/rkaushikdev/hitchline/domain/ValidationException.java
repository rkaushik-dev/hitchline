package io.github.rkaushikdev.hitchline.domain;

import java.util.List;
import java.util.Optional;

/** A payload, a value or an argument that breaks a rule of the domain. */
public class ValidationException extends DomainException {

    public ValidationException(String message) {
        this(message, List.of());
    }

    public ValidationException(String message, List<ErrorDetail> details) {
        super(ErrorCode.VALIDATION_FAILED, message, details);
    }

    /** A failure of one field, whose message reads "path message". */
    public static ValidationException field(String path, String message) {
        return new ValidationException(path + " " + message, List.of(new ErrorDetail(path, message)));
    }

    /**
     * Collects the details of several validation failures into one, so a caller sees every
     * problem with a payload instead of only the first. A failure with no details of its own is
     * kept by pinning its message to the empty path. Nothing failed, nothing is returned.
     */
    public static Optional<ValidationException> combine(String message, List<ValidationException> errors) {
        if (errors.isEmpty()) {
            return Optional.empty();
        }
        List<ErrorDetail> details = errors.stream()
                .flatMap(error -> error.details().isEmpty()
                        ? java.util.stream.Stream.of(new ErrorDetail("", error.getMessage()))
                        : error.details().stream())
                .toList();
        return Optional.of(new ValidationException(message, details));
    }
}
