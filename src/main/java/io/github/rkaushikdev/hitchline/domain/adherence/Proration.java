package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.domain.schedule.Obligation;
import io.github.rkaushikdev.hitchline.domain.schedule.ObligationScope;
import java.util.List;

/**
 * Scales an obligation measured over many days down to the part of it nobody shielded. Three gym
 * sessions a hitch is a fair ask over a fortnight offshore and an unfair one when a week of it was
 * spent in the sick bay, so the count is cut in proportion to the days left, rounding up so that a
 * part hitch still asks for something. A one day obligation is left alone, because a shield on
 * that day already excuses it, and so is one shielded end to end.
 */
public final class Proration {

    private Proration() {}

    /** The obligation with its count cut to the share of its window the member's shields leave. */
    public static Obligation prorate(Obligation obligation, List<Shield> shields, String memberId) {
        if (obligation.scope() == ObligationScope.DAY || obligation.span() == 1) {
            return obligation;
        }
        int shielded = Shield.shieldedDayCount(shields, memberId, obligation.window());
        int left = obligation.span() - shielded;
        if (shielded == 0 || left == 0) {
            return obligation;
        }
        int times = (int) Math.ceil(obligation.times() * (double) left / obligation.span());
        return obligation.withTimes(Math.max(1, times));
    }

    /** Every obligation prorated, in the order given. */
    public static List<Obligation> prorateAll(List<Obligation> obligations, List<Shield> shields, String memberId) {
        return obligations.stream()
                .map(obligation -> prorate(obligation, shields, memberId))
                .toList();
    }
}
