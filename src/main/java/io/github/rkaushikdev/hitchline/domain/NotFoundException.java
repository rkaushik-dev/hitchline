package io.github.rkaushikdev.hitchline.domain;

/** A record that was asked for by id and is not there. */
public class NotFoundException extends DomainException {

    private final String resource;
    private final String id;

    public NotFoundException(String resource, String id) {
        super(ErrorCode.NOT_FOUND, resource + " " + id + " does not exist");
        this.resource = resource;
        this.id = id;
    }

    /** The kind of record, such as {@code habit}. */
    public String resource() {
        return resource;
    }

    public String id() {
        return id;
    }
}
