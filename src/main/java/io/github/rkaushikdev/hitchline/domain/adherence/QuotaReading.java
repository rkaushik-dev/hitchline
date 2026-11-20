package io.github.rkaushikdev.hitchline.domain.adherence;

import java.util.List;

/**
 * What a quota made of a run of results.
 *
 * @param quota the rule read
 * @param worst the most failures any one window held
 * @param breaches every stretch that broke the rule, a run of failures reported once
 */
public record QuotaReading(Quota quota, int worst, List<QuotaBreach> breaches) {

    public QuotaReading {
        breaches = List.copyOf(breaches);
    }

    public boolean isBreached() {
        return !breaches.isEmpty();
    }

    /** How much room is left before the rule bites, given the worst stretch seen. */
    public int headroom() {
        return Math.max(0, quota.allowed() - worst);
    }
}
