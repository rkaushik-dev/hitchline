package io.github.rkaushikdev.hitchline.domain.checkin;

/**
 * What adding an entry to the ledger did.
 *
 * @param ledger the ledger after the append
 * @param accepted false when the idempotency key had already been used
 * @param entry the entry now standing for the report: the new one, or the one that used the key
 */
public record AppendResult(Ledger ledger, boolean accepted, CheckIn entry) {}
