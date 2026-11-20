package io.github.rkaushikdev.hitchline.domain;

import java.util.List;

/** A change that disagrees with what is already recorded. */
public class ConflictException extends DomainException {

    public ConflictException(String message) {
        this(message, List.of());
    }

    public ConflictException(String message, List<ErrorDetail> details) {
        super(ErrorCode.CONFLICT, message, details);
    }
}
