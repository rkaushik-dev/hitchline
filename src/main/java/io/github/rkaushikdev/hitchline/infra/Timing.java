package io.github.rkaushikdev.hitchline.infra;

import io.github.rkaushikdev.hitchline.util.Numbers;

/**
 * How long a body of work took over several runs, each figure to the microsecond.
 *
 * @param name what was measured
 * @param runs how many timed runs
 * @param medianMs the median run
 * @param worstMs the slowest run
 * @param totalMs all the timed runs together
 */
public record Timing(String name, int runs, double medianMs, double worstMs, double totalMs) {

    /** One line, such as {@code expand: 1.25ms median over 5 runs, worst 2ms}. */
    public String describe() {
        return name + ": " + Numbers.format(medianMs) + "ms median over " + runs + " runs, worst "
                + Numbers.format(worstMs) + "ms";
    }

    /** True when the median is slower than the budget it was given. */
    public boolean isSlowerThan(double budgetMs) {
        return medianMs > budgetMs;
    }
}
