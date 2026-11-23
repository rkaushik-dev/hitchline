package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;

/**
 * How somebody did on the days of one role.
 *
 * @param role the role of the days
 * @param owed obligations that fell on them
 * @param met how many of those were met
 * @param partial how many closed partly done
 * @param missed how many closed with nothing done
 * @param rate the adherence rate over them
 */
public record RoleSplit(DayRole role, int owed, int met, int partial, int missed, double rate) {

    /** The split for one role, from the tally of what fell on it. */
    public static RoleSplit of(DayRole role, StatusTally tally) {
        return new RoleSplit(role, tally.owed(), tally.met(), tally.partial(), tally.missed(), tally.rate());
    }
}
