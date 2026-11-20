package io.github.rkaushikdev.hitchline.infra.store;

/**
 * What restoring a backup did.
 *
 * @param restored entries written back
 * @param takenAt when the backup was taken
 */
public record RestoreReport(int restored, String takenAt) {}
