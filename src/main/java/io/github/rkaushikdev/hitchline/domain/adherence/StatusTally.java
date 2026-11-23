package io.github.rkaushikdev.hitchline.domain.adherence;

import java.util.List;

/**
 * How many results had each status, and the rate they make. Every report used to count these for
 * itself, and three of them only ever counted the misses, so a run of days done halfway looked
 * the same as a run of days done properly. One tally now feeds them all.
 *
 * @param met obligations met
 * @param partial obligations closed with something done but not enough
 * @param missed obligations closed with nothing done
 * @param pending obligations still open
 */
public record StatusTally(int met, int partial, int missed, int pending) {

    /** Nothing counted at all. */
    public static final StatusTally NONE = new StatusTally(0, 0, 0, 0);

    /** The results counted by status. */
    public static StatusTally of(List<ObligationResult> results) {
        int met = 0;
        int partial = 0;
        int missed = 0;
        int pending = 0;
        for (ObligationResult result : results) {
            switch (result.status()) {
                case MET -> met += 1;
                case PARTIAL -> partial += 1;
                case MISSED -> missed += 1;
                case PENDING -> pending += 1;
            }
        }
        return new StatusTally(met, partial, missed, pending);
    }

    /** Everything counted. */
    public int owed() {
        return met + partial + missed + pending;
    }

    /** Everything that can no longer change. */
    public int settled() {
        return met + partial + missed;
    }

    /**
     * Share of the settled obligations that were met. Pending ones are left out of both halves of
     * the fraction rather than counted as failures; with nothing settled the rate is one.
     */
    public double rate() {
        return settled() == 0 ? 1 : met / (double) settled();
    }

    /** What closed short, partly done or not done at all. */
    public int closedShort() {
        return partial + missed;
    }
}
