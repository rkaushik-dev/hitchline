package io.github.rkaushikdev.hitchline.domain.adherence;

import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.List;

/**
 * A rule about how much slipping is tolerated. A streak says how good the last run was; a quota
 * says whether somebody is in trouble, which is a different question and the one a supervisor is
 * actually asked.
 *
 * @param name what the rule is called, trimmed and never empty
 * @param allowed how many misses are allowed inside the window before the rule bites
 * @param windowDays the window, 1 to 365 days
 * @param countPartial whether a partial counts against the quota, or only an outright miss
 */
public record Quota(String name, int allowed, int windowDays, boolean countPartial) {

    static final int MAX_WINDOW_DAYS = 365;

    public Quota {
        name = Texts.trim(name);
        if (name.isEmpty()) {
            throw ValidationException.field("name", "must not be empty");
        }
        if (allowed < 0) {
            throw ValidationException.field("allowed", "must be zero or more, got " + allowed);
        }
        if (windowDays < 1 || windowDays > MAX_WINDOW_DAYS) {
            throw ValidationException.field("windowDays", "must be between 1 and " + MAX_WINDOW_DAYS);
        }
    }

    /** A quota that counts partials as well as misses. */
    public static Quota of(String name, int allowed, int windowDays) {
        return new Quota(name, allowed, windowDays, true);
    }

    public static Quota of(String name, int allowed, int windowDays, boolean countPartial) {
        return new Quota(name, allowed, windowDays, countPartial);
    }

    private boolean countsAgainst(ObligationResult result) {
        if (result.status() == AdherenceStatus.MISSED) {
            return true;
        }
        return countPartial && result.status() == AdherenceStatus.PARTIAL;
    }

    /**
     * Slides the window over every day something was dropped and reports each stretch where too
     * much was. Windows are anchored on the days themselves, so the reading does not depend on where
     * a caller happened to start looking; a stretch that starts inside the last one reported is part
     * of it.
     */
    public QuotaReading read(List<ObligationResult> results) {
        List<CivilDate> failures = results.stream()
                .filter(this::countsAgainst)
                .map(result -> result.obligation().dueOn())
                .sorted()
                .toList();
        List<QuotaBreach> breaches = new ArrayList<>();
        int worst = 0;
        for (CivilDate start : failures) {
            DateRange window = DateRange.of(start, start.plusDays(windowDays - 1L));
            int inside = (int) failures.stream().filter(window::contains).count();
            worst = Math.max(worst, inside);
            if (inside > allowed) {
                if (!breaches.isEmpty()
                        && breaches.get(breaches.size() - 1).window().contains(start)) {
                    continue;
                }
                breaches.add(new QuotaBreach(this, window, inside, start));
            }
        }
        return new QuotaReading(this, worst, breaches);
    }

    /** One line, such as {@code weekly slip: at most 2 misses or partials in 7 days}. */
    public String describe() {
        String what = countPartial ? "misses or partials" : "misses";
        return name + ": at most " + allowed + " " + what + " in " + windowDays + " days";
    }
}
