package io.github.rkaushikdev.hitchline.domain;

/** A request the domain understands but does not do. */
public class UnsupportedException extends DomainException {

    public UnsupportedException(String message) {
        super(ErrorCode.UNSUPPORTED, message);
    }
}
