package io.github.rkaushikdev.hitchline.domain.time;

import java.time.Instant;

/**
 * A wall clock reading resolved onto the timeline.
 *
 * @param instant where it landed
 * @param outcome whether the reading was exact, inside a gap or inside an overlap
 * @param offsetMinutes the zone's offset east of UTC at that instant
 */
public record ZonedResolution(Instant instant, ZonedOutcome outcome, int offsetMinutes) {}
