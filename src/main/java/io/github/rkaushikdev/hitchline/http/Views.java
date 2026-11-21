package io.github.rkaushikdev.hitchline.http;

import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.adherence.PeriodSummary;
import io.github.rkaushikdev.hitchline.domain.adherence.Shield;
import io.github.rkaushikdev.hitchline.domain.adherence.StreakSummary;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.crew.Crew;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.crew.Membership;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitTarget;
import io.github.rkaushikdev.hitchline.domain.nudge.PlannedNudge;
import io.github.rkaushikdev.hitchline.domain.rotation.RosterOverride;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Numbers;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Wire shapes. Nothing in the domain knows about JSON, so every response body is built here and the
 * domain types stay free of presentation. A view's components are written in declaration order, and
 * an absent one is written as null.
 */
public final class Views {

    private Views() {}

    /** A ratio to two decimal places, a tie rounding up. */
    static double twoPlaces(double ratio) {
        return Numbers.roundTo(ratio, 2);
    }

    /**
     * A habit.
     *
     * @param id its id
     * @param memberId whose it is
     * @param name what it is called
     * @param cadence how often, as the domain writes it
     * @param target its target, as the domain writes it
     * @param status active, paused or archived
     * @param startsOn the first day it asks
     * @param endsOn the last day it asks
     * @param tags short labels
     * @param graceDays how late a check in still counts
     */
    public record HabitView(
            String id,
            String memberId,
            String name,
            String cadence,
            Optional<String> target,
            String status,
            String startsOn,
            Optional<String> endsOn,
            List<String> tags,
            int graceDays) {}

    public static HabitView habitView(Habit entry) {
        return new HabitView(
                entry.id(),
                entry.memberId(),
                entry.name(),
                entry.cadence().format(),
                entry.target().map(HabitTarget::format),
                entry.status().wire(),
                entry.startsOn().toString(),
                entry.endsOn().map(CivilDate::toString),
                entry.tags(),
                entry.graceDays());
    }

    /**
     * A crew member.
     *
     * @param id their id
     * @param displayName the name shown
     * @param homeZone where they live
     * @param siteZone where they work
     * @param pattern the rotation, such as {@code 14on 14off}
     * @param anchor the date at the rotation's phase position
     * @param phase how far into the cycle the anchor is
     * @param status active or not
     */
    public record MemberView(
            String id,
            String displayName,
            String homeZone,
            String siteZone,
            String pattern,
            String anchor,
            int phase,
            String status) {}

    public static MemberView memberView(CrewMember member) {
        return new MemberView(
                member.id(),
                member.displayName(),
                member.homeZone(),
                member.siteZone(),
                member.rotation().pattern().format(),
                member.rotation().anchor().toString(),
                member.rotation().phase(),
                member.status().wire());
    }

    /**
     * A stored check in on its own. It carries no rotation day, because a row that has not been
     * resolved does not know which one it belongs to and a null there only ever got mistaken for "no
     * day".
     *
     * @param id its id
     * @param habitId which habit
     * @param at when it happened
     * @param reading the number it carried
     * @param note anything written
     * @param source how it reached us
     */
    public record CheckInView(
            String id, String habitId, String at, OptionalDouble reading, String note, String source) {}

    public static CheckInView checkInView(CheckIn entry) {
        return new CheckInView(
                entry.id(),
                entry.habitId(),
                Zones.formatInstant(entry.at()),
                entry.reading(),
                entry.note(),
                entry.source().wire());
    }

    /**
     * A check in with the rotation day it landed on.
     *
     * @param id its id
     * @param habitId which habit
     * @param at when it happened
     * @param reading the number it carried
     * @param note anything written
     * @param source how it reached us
     * @param onDate the rotation day
     * @param zone the zone that day is measured in
     */
    public record ResolvedCheckInView(
            String id,
            String habitId,
            String at,
            OptionalDouble reading,
            String note,
            String source,
            String onDate,
            String zone) {}

    public static ResolvedCheckInView resolvedView(ResolvedCheckIn item) {
        CheckInView base = checkInView(item.checkIn());
        return new ResolvedCheckInView(
                base.id(),
                base.habitId(),
                base.at(),
                base.reading(),
                base.note(),
                base.source(),
                item.day().date().toString(),
                item.day().zone());
    }

    /**
     * One obligation and how it went.
     *
     * @param habitId which habit
     * @param scope day, hitch or window
     * @param window the days it covers
     * @param dueOn the day it is reported against
     * @param times how many check ins it asks for
     * @param count how many it got
     * @param total the summed reading, for a habit with a target
     * @param status how it went
     * @param progress the share done, to two places
     */
    public record ResultView(
            String habitId,
            String scope,
            String window,
            String dueOn,
            int times,
            int count,
            OptionalDouble total,
            String status,
            double progress) {}

    public static ResultView resultView(ObligationResult result) {
        return new ResultView(
                result.obligation().habitId(),
                result.obligation().scope().wire(),
                result.obligation().window().toString(),
                result.obligation().dueOn().toString(),
                result.obligation().times(),
                result.count(),
                result.total(),
                result.status().wire(),
                twoPlaces(result.progress()));
    }

    /**
     * How a stretch of days went.
     *
     * @param window the days
     * @param owed obligations due
     * @param met obligations met
     * @param partial obligations part met
     * @param missed obligations missed
     * @param pending obligations still open
     * @param shieldedDays days shielded from scoring
     * @param rate the share met, to two places
     */
    public record SummaryView(
            String window, int owed, int met, int partial, int missed, int pending, int shieldedDays, double rate) {}

    public static SummaryView summaryView(PeriodSummary summary) {
        return new SummaryView(
                summary.range().toString(),
                summary.owed(),
                summary.met(),
                summary.partial(),
                summary.missed(),
                summary.pending(),
                summary.shieldedDays(),
                twoPlaces(summary.rate()));
    }

    /**
     * A run of met obligations.
     *
     * @param current the run going now
     * @param longest the longest run in the window
     * @param shielded shielded days stepped over
     * @param lastMetOn the last day one was met
     * @param brokenOn the day the run last broke
     */
    public record StreakView(
            int current, int longest, int shielded, Optional<String> lastMetOn, Optional<String> brokenOn) {}

    public static StreakView streakView(StreakSummary summary) {
        return new StreakView(
                summary.current(),
                summary.longest(),
                summary.shielded(),
                summary.lastMetOn().map(CivilDate::toString),
                summary.brokenOn().map(CivilDate::toString));
    }

    /**
     * A roster entry.
     *
     * @param memberId whose roster
     * @param range the days
     * @param role what the days became
     * @param reason why
     * @param note anything written with it
     * @param enteredAt when it was entered
     */
    public record OverrideView(
            String memberId, String range, String role, String reason, String note, String enteredAt) {}

    public static OverrideView overrideView(RosterOverride entry) {
        return new OverrideView(
                entry.memberId(),
                entry.range().toString(),
                entry.role().wire(),
                entry.reason().wire(),
                entry.note(),
                Zones.formatInstant(entry.enteredAt()));
    }

    /**
     * Days shielded from scoring.
     *
     * @param memberId whose days
     * @param range the days
     * @param reason why
     * @param note anything written with it
     * @param grantedBy who granted it; absent when self declared
     * @param grantedAt when
     */
    public record ShieldView(
            String memberId, String range, String reason, String note, Optional<String> grantedBy, String grantedAt) {}

    public static ShieldView shieldView(Shield entry) {
        return new ShieldView(
                entry.memberId(),
                entry.range().toString(),
                entry.reason().wire(),
                entry.note(),
                entry.grantedBy(),
                Zones.formatInstant(entry.grantedAt()));
    }

    /**
     * A crew.
     *
     * @param id its id
     * @param name what it is called
     * @param site where it works
     * @param siteZone the site's time zone
     */
    public record CrewView(String id, String name, String site, String siteZone) {}

    public static CrewView crewView(Crew entry) {
        return new CrewView(entry.id(), entry.name(), entry.site(), entry.siteZone());
    }

    /**
     * Somebody's place in a crew.
     *
     * @param crewId which crew
     * @param memberId who
     * @param role what they do in it
     * @param from the first day
     * @param to the last day, when it has ended
     */
    public record MembershipView(String crewId, String memberId, String role, String from, Optional<String> to) {}

    public static MembershipView membershipView(Membership entry) {
        return new MembershipView(
                entry.crewId(),
                entry.memberId(),
                entry.role().wire(),
                entry.from().toString(),
                entry.to().map(CivilDate::toString));
    }

    /**
     * A nudge the planner would send.
     *
     * @param habitId which habit
     * @param key what makes it unique
     * @param at when it would go
     * @param onDate the rotation day it is about
     * @param reason why
     */
    public record NudgeView(String habitId, String key, String at, String onDate, String reason) {}

    public static NudgeView nudgeView(PlannedNudge nudge) {
        return new NudgeView(
                nudge.habitId(),
                nudge.key(),
                Zones.formatInstant(nudge.at()),
                nudge.onDate().toString(),
                nudge.reason().wire());
    }
}
