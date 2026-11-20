package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;

/**
 * How somebody did on the days of one role.
 *
 * @param role the role of the days
 * @param owed obligations that fell on them
 * @param met how many of those were met
 * @param rate the adherence rate over them
 */
public record RoleSplit(DayRole role, int owed, int met, double rate) {}
