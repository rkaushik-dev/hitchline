package io.github.rkaushikdev.hitchline.domain.rotation;

/**
 * Which clock a day is measured on, and why.
 *
 * @param zone the tz database id of the clock
 * @param reason why that clock
 */
public record ZoneChoice(String zone, ZoneReason reason) {}
