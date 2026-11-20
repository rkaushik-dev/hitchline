package io.github.rkaushikdev.hitchline.domain.adherence;

/**
 * The check ins made in one local hour of the day.
 *
 * @param hour the hour, 0 to 23, on the clock of the day each check in landed on
 * @param count how many
 */
public record HourBucket(int hour, int count) {}
