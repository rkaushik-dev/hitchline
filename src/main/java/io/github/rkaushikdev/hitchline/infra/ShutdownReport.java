package io.github.rkaushikdev.hitchline.infra;

import java.util.List;

/**
 * What a shutdown did.
 *
 * @param ran how many tasks were run
 * @param failed the names of the ones that threw, in the order they ran
 */
public record ShutdownReport(int ran, List<String> failed) {

    public ShutdownReport {
        failed = List.copyOf(failed);
    }
}
