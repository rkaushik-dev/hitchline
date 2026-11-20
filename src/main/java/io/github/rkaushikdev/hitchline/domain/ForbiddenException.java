package io.github.rkaushikdev.hitchline.domain;

/** A caller asking for something its token or its sharing settings do not allow. */
public class ForbiddenException extends DomainException {

    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }
}
