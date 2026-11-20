package io.github.rkaushikdev.hitchline.app;

import io.github.rkaushikdev.hitchline.domain.IdKind;

/** Where new record ids come from. */
@FunctionalInterface
public interface IdSource {

    String next(IdKind kind);
}
