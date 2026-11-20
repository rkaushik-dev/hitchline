package io.github.rkaushikdev.hitchline.domain.adherence;

/**
 * How many results had each status.
 *
 * @param met obligations met
 * @param partial obligations closed with something done but not enough
 * @param missed obligations closed with nothing done
 * @param pending obligations still open
 */
public record StatusTally(int met, int partial, int missed, int pending) {}
