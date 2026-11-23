package io.github.rkaushikdev.hitchline.cli.commands;

import io.github.rkaushikdev.hitchline.app.AdherenceService;
import io.github.rkaushikdev.hitchline.app.AdherenceService.AdherenceReport;
import io.github.rkaushikdev.hitchline.app.NudgesService;
import io.github.rkaushikdev.hitchline.cli.Column;
import io.github.rkaushikdev.hitchline.cli.Context;
import io.github.rkaushikdev.hitchline.cli.Render;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.adherence.FreezeBudget;
import io.github.rkaushikdev.hitchline.domain.adherence.FrozenStreak;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.adherence.PeriodSummary;
import io.github.rkaushikdev.hitchline.domain.adherence.RestBreach;
import io.github.rkaushikdev.hitchline.domain.adherence.RestHoursRule;
import io.github.rkaushikdev.hitchline.domain.adherence.RoleSplit;
import io.github.rkaushikdev.hitchline.domain.adherence.StreakSummary;
import io.github.rkaushikdev.hitchline.domain.nudge.PlannedNudge;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Reports. These are what somebody actually runs on a Monday morning, so they print the numbers a
 * crew argues about: how much was owed, how much landed, and which hitch it happened in.
 */
public final class ReportCommands {

    private ReportCommands() {}

    /** The {@code report} commands. */
    public static CommandLine report(Supplier<Context> context) {
        return Arguments.group(
                "report",
                "read adherence back out",
                new ReportAdherence(context),
                new ReportOpen(context),
                new ReportStreak(context),
                new ReportNudges(context));
    }

    /** What every report takes: whose it is and the window it reads. */
    abstract static class MemberReport extends ContextCommand {

        @Parameters(index = "0", paramLabel = "<memberId>")
        String memberId;

        @Option(
                names = "--window",
                required = true,
                paramLabel = "<window>",
                description = "window such as 2025-01-06..2025-01-19")
        String window;

        MemberReport(Supplier<Context> context) {
            super(context);
        }

        String member() {
            return Ids.crewMemberId(memberId);
        }
    }

    @Command(name = "adherence", description = "how a window went", showDefaultValues = true)
    static final class ReportAdherence extends MemberReport {

        @Option(names = "--by-hitch", description = "break it down by turn of the rotation")
        private boolean byHitch;

        @Option(names = "--shape", description = "show the role split, the trend and the busiest hour")
        private boolean shape;

        @Option(
                names = "--rest-hours",
                paramLabel = "<habitId>",
                description = "check a rest log against 10 hours a day and 77 a week")
        private String restHours;

        ReportAdherence(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            String id = member();
            AdherenceService service = new AdherenceService(ctx.ports());
            AdherenceReport report = service.report(id, window);
            ctx.out(Render.heading("adherence for " + memberId));
            ctx.out(Render.keyValues(summaryLines(report)));
            if (byHitch) {
                ctx.out("");
                ctx.out(Render.renderTable(report.byHitch(), summaryColumns()));
            }
            if (shape) {
                ctx.out("");
                ctx.out(Render.renderTable(report.byRole(), roleColumns()));
                ctx.out("");
                ctx.out(Render.keyValues(shapeLines(report)));
            }
            if (restHours != null) {
                List<RestBreach> breaches =
                        service.restHours(id, Ids.habitId(restHours), window, RestHoursRule.DEFAULT);
                ctx.out("");
                ctx.out(breaches.isEmpty() ? "inside the rest hour rules" : "rest hour breaches");
                breaches.forEach(breach -> ctx.out(breach.describe()));
            }
        }

        private static List<Map.Entry<String, String>> summaryLines(AdherenceReport report) {
            PeriodSummary summary = report.summary();
            return List.of(
                    Map.entry("window", report.window().toString()),
                    Map.entry("as of", report.asOf().toString()),
                    Map.entry("owed", Integer.toString(summary.owed())),
                    Map.entry("met", Integer.toString(summary.met())),
                    Map.entry("partial", Integer.toString(summary.partial())),
                    Map.entry("missed", Integer.toString(summary.missed())),
                    Map.entry("pending", Integer.toString(summary.pending())),
                    Map.entry("shielded", Render.pluralise(summary.shieldedDays(), "day")),
                    Map.entry("rate", Render.bar(summary.rate()) + " " + Render.percent(summary.rate())));
        }

        private static List<Map.Entry<String, String>> shapeLines(AdherenceReport report) {
            String busiest = report.busiestHour().isPresent()
                    ? Texts.padStart(Integer.toString(report.busiestHour().getAsInt()), 2, '0') + ":00"
                    : "nothing recorded";
            return List.of(Map.entry("trend", report.trend().describe()), Map.entry("busiest hour", busiest));
        }
    }

    @Command(name = "open", description = "what is still open in a window")
    static final class ReportOpen extends MemberReport {

        ReportOpen(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            List<ObligationResult> open = new AdherenceService(ctx.ports()).open(member(), window);
            ctx.out(Render.renderTable(open, resultColumns()));
        }
    }

    @Command(name = "streak", description = "the run on one habit")
    static final class ReportStreak extends MemberReport {

        @Parameters(index = "1", paramLabel = "<habitId>")
        private String habitId;

        @Option(
                names = "--freezes",
                paramLabel = "<misses/days>",
                description = "let the run carry this many misses in so many days, such as 1/28")
        private String freezes;

        ReportStreak(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            AdherenceService service = new AdherenceService(ctx.ports());
            String habit = Ids.habitId(habitId);
            if (freezes == null) {
                StreakSummary summary = service.streak(member(), habit, window);
                ctx.out(Render.keyValues(runLines(
                        summary.current(),
                        summary.longest(),
                        summary.shielded(),
                        summary.lastMetOn(),
                        summary.brokenOn())));
                return;
            }
            FreezeBudget budget = FreezeBudget.parse(freezes);
            FrozenStreak run = service.frozenStreak(member(), habit, window, budget);
            List<Map.Entry<String, String>> lines = new ArrayList<>(
                    runLines(run.current(), run.longest(), run.shielded(), run.lastMetOn(), run.brokenOn()));
            lines.add(Map.entry("carried", Integer.toString(run.frozen())));
            lines.add(Map.entry("freezes left", run.freezesLeft() + " of " + budget.format()));
            ctx.out(Render.keyValues(lines));
        }

        private static List<Map.Entry<String, String>> runLines(
                int current, int longest, int shielded, Optional<CivilDate> lastMet, Optional<CivilDate> brokenOn) {
            return List.of(
                    Map.entry("current", Integer.toString(current)),
                    Map.entry("longest", Integer.toString(longest)),
                    Map.entry("shielded", Integer.toString(shielded)),
                    Map.entry("last met", dayOrDash(lastMet)),
                    Map.entry("broken on", dayOrDash(brokenOn)));
        }

        private static String dayOrDash(Optional<CivilDate> day) {
            return day.map(CivilDate::toString).orElse("-");
        }
    }

    @Command(name = "nudges", description = "what the planner would send")
    static final class ReportNudges extends MemberReport {

        ReportNudges(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            List<PlannedNudge> planned = new NudgesService(ctx.ports()).plan(member(), window);
            ctx.out(Render.renderTable(planned, nudgeColumns()));
        }
    }

    private static List<Column<PlannedNudge>> nudgeColumns() {
        return List.of(
                Column.left("day", row -> row.onDate().toString()),
                Column.left("at", row -> Zones.formatInstant(row.at())),
                Column.left("why", row -> row.reason().wire()),
                Column.left("habit", PlannedNudge::habitId));
    }

    private static List<Column<RoleSplit>> roleColumns() {
        return List.of(
                Column.left("role", row -> row.role().wire()),
                Column.right("owed", row -> Integer.toString(row.owed())),
                Column.right("met", row -> Integer.toString(row.met())),
                Column.right("partial", row -> Integer.toString(row.partial())),
                Column.right("missed", row -> Integer.toString(row.missed())),
                Column.right("rate", row -> Render.percent(row.rate())));
    }

    private static List<Column<PeriodSummary>> summaryColumns() {
        return List.of(
                Column.left("window", row -> row.range().toString()),
                Column.right("owed", row -> Integer.toString(row.owed())),
                Column.right("met", row -> Integer.toString(row.met())),
                Column.right("missed", row -> Integer.toString(row.missed())),
                Column.right("rate", row -> Render.percent(row.rate())));
    }

    private static List<Column<ObligationResult>> resultColumns() {
        return List.of(
                Column.left("due", row -> row.obligation().dueOn().toString()),
                Column.left("scope", row -> row.obligation().scope().wire()),
                Column.left("habit", row -> row.obligation().habitId()),
                Column.right("done", row -> row.count() + "/" + row.obligation().times()),
                Column.left("status", row -> row.status().wire()));
    }
}
