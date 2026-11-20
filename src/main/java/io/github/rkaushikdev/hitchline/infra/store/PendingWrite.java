package io.github.rkaushikdev.hitchline.infra.store;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A line still to be written: everything but its sequence number.
 *
 * @param kind the kind of record
 * @param op what the line does
 * @param row the record
 * @param at when it is being written
 */
public record PendingWrite(RecordKind kind, Operation op, JsonNode row, String at) {}
