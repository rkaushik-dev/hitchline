package io.github.rkaushikdev.hitchline.infra.store;

import java.util.List;

/**
 * What pruning a backup directory did, or would do on a dry run.
 *
 * @param kept the backups kept, newest first
 * @param removed the backups removed
 */
public record PruneReport(List<String> kept, List<String> removed) {

    public PruneReport {
        kept = List.copyOf(kept);
        removed = List.copyOf(removed);
    }
}
