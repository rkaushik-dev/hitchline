package io.github.rkaushikdev.hitchline.domain.time;

import java.time.Instant;

/**
 * The instants a local day covers, half open.
 *
 * @param from the first instant of the local day
 * @param to the first instant of the next local day
 */
public record DayBounds(Instant from, Instant to) {

    public boolean contains(Instant at) {
        return !at.isBefore(from) && at.isBefore(to);
    }
}
