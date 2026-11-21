package io.github.rkaushikdev.hitchline.http.routes;

import io.github.rkaushikdev.hitchline.app.AdherenceService;
import io.github.rkaushikdev.hitchline.app.AdherenceService.AdherenceReport;
import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.adherence.HabitSummary;
import io.github.rkaushikdev.hitchline.domain.adherence.Trend;
import io.github.rkaushikdev.hitchline.http.Auth;
import io.github.rkaushikdev.hitchline.http.HttpApp;
import io.github.rkaushikdev.hitchline.http.Query;
import io.github.rkaushikdev.hitchline.http.Views;
import io.github.rkaushikdev.hitchline.http.Views.ResultView;
import io.github.rkaushikdev.hitchline.http.Views.StreakView;
import io.github.rkaushikdev.hitchline.http.Views.SummaryView;
import io.github.rkaushikdev.hitchline.util.Numbers;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.OptionalInt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read routes a crew actually looks at: how a window went, what a run looks like, and what is
 * still open today.
 */
@RestController
public final class AdherenceRoutes {

    private final AdherenceService adherence;

    public AdherenceRoutes(Ports ports) {
        this.adherence = new AdherenceService(ports);
    }

    /** Registers the adherence routes on {@code app}. */
    public static void register(HttpApp app, Ports ports) {
        app.addRoutes(new AdherenceRoutes(ports));
    }

    /**
     * How one habit went over the window.
     *
     * @param habitId which habit
     * @param window the days
     * @param owed obligations due
     * @param met obligations met
     * @param partial obligations part met
     * @param missed obligations missed
     * @param pending obligations still open
     * @param shieldedDays days shielded from scoring
     * @param rate the share met, to two places
     */
    public record HabitSummaryView(
            String habitId,
            String window,
            int owed,
            int met,
            int partial,
            int missed,
            int pending,
            int shieldedDays,
            double rate) {}

    /**
     * How the days of one role went.
     *
     * @param role the day role
     * @param owed obligations due on those days
     * @param met obligations met
     * @param rate the share met, to two places
     */
    public record RoleView(String role, int owed, int met, double rate) {}

    /**
     * How a window went.
     *
     * @param asOf the day the report was read on
     * @param summary the whole window
     * @param byHabit one line per habit
     * @param byHitch one line per hitch
     * @param byRole one line per day role
     * @param trend which way the last hitch moved
     * @param busiestHour the local hour most check ins happened in
     * @param results every obligation and how it went
     */
    public record AdherenceBody(
            String asOf,
            SummaryView summary,
            List<HabitSummaryView> byHabit,
            List<SummaryView> byHitch,
            List<RoleView> byRole,
            Trend trend,
            OptionalInt busiestHour,
            List<ResultView> results) {}

    /**
     * What is still open.
     *
     * @param open the obligations not yet settled
     */
    public record OpenBody(List<ResultView> open) {}

    private static HabitSummaryView habitSummaryView(HabitSummary entry) {
        return new HabitSummaryView(
                entry.habitId(),
                entry.range().toString(),
                entry.owed(),
                entry.met(),
                entry.partial(),
                entry.missed(),
                entry.pending(),
                entry.shieldedDays(),
                Numbers.roundTo(entry.rate(), 2));
    }

    @GetMapping("/members/{memberId}/adherence")
    public AdherenceBody adherence(@PathVariable("memberId") String memberId, HttpServletRequest request) {
        String member = Ids.crewMemberId(memberId);
        Auth.requireSelfOrAdmin(request, member);
        AdherenceReport report = adherence.report(member, Query.windowStringOf(request, "read adherence"));
        return new AdherenceBody(
                report.asOf().toString(),
                Views.summaryView(report.summary()),
                report.byHabit().stream().map(AdherenceRoutes::habitSummaryView).toList(),
                report.byHitch().stream().map(Views::summaryView).toList(),
                report.byRole().stream()
                        .map(split -> new RoleView(
                                split.role().wire(), split.owed(), split.met(), Numbers.roundTo(split.rate(), 2)))
                        .toList(),
                report.trend(),
                report.busiestHour(),
                report.results().stream().map(Views::resultView).toList());
    }

    @GetMapping("/members/{memberId}/habits/{habitId}/streak")
    public StreakView streak(
            @PathVariable("memberId") String memberId,
            @PathVariable("habitId") String habitId,
            HttpServletRequest request) {
        String member = Ids.crewMemberId(memberId);
        Auth.requireSelfOrAdmin(request, member);
        return Views.streakView(
                adherence.streak(member, Ids.habitId(habitId), Query.windowStringOf(request, "read a streak")));
    }

    @GetMapping("/members/{memberId}/open")
    public OpenBody open(@PathVariable("memberId") String memberId, HttpServletRequest request) {
        String member = Ids.crewMemberId(memberId);
        Auth.requireSelfOrAdmin(request, member);
        return new OpenBody(adherence.open(member, Query.windowStringOf(request, "read what is open")).stream()
                .map(Views::resultView)
                .toList());
    }
}
