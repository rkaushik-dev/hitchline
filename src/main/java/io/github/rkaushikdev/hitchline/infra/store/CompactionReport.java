package io.github.rkaushikdev.hitchline.infra.store;

/**
 * What compacting a journal did, or would do.
 *
 * @param before entries read
 * @param after entries kept
 * @param dropped entries dropped
 * @param skipped lines that could not be read at all
 */
public record CompactionReport(int before, int after, int dropped, int skipped) {

    /** True when enough would be dropped that compacting is worth the write: 30 percent by default. */
    public boolean worthCompacting() {
        return worthCompacting(0.3);
    }

    public boolean worthCompacting(double ratio) {
        if (before == 0) {
            return false;
        }
        return dropped / (double) before >= ratio;
    }
}
