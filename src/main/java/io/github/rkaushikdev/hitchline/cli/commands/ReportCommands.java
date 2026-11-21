package io.github.rkaushikdev.hitchline.cli.commands;

import io.github.rkaushikdev.hitchline.app.AdherenceService;
import io.github.rkaushikdev.hitchline.app.AdherenceService.AdherenceReport;
import io.github.rkaushikdev.hitchline.app.NudgesService;
import io.github.rkaushikdev.hitchline.cli.Column;
import io.github.rkaushikdev.hitchline.cli.Context;
import io.github.rkaushikdev.hitchline.cli.Render;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.adherence.ObligationResult;
import io.github.rkaushikdev.hitchline.domain.adherence.PeriodSummary;
import io.github.rkaushikdev.hitchline.domain.adherence.RoleSplit;
import io.github.rkaushikdev.hitchline.domain.adherence.StreakSummary;
import io.github.rkaushikdev.hitchline.domain.nudge.PlannedNudge;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.util.Texts;
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

    @Command(name = "adherence", description = "how a window went", showDefaultValues = true)
    static final class ReportAdherence extends ContextCommand {

        @Parameters(index = "0", paramLabel = "<memberId>")
        private String memberId;

        @Option(
                names = "--window",
                required = true,
                paramLabel = "<window>",
                description = "window such as 2025-01-06..2025-01-19")
        private String window;

        @Option(names = "--by-hitch", description = "break it down by turn of the rotation")
        private boolean byHitch;

        @Option(names = "--shape", description = "show the role split, the trend and the busiest hour")
        private boolean shape;

        ReportAdherence(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            AdherenceReport report = new AdherenceService(ctx.ports()).report(Ids.crewMemberId(memberId), window);
            PeriodSummary summary = report.summary();
            ctx.out(Render.heading("adherence for " + memberId));
            ctx.out(Render.keyValues(List.of(
                    Map.entry("window", report.window().toString()),
                    Map.entry("as of", report.asOf().toString()),
                    Map.entry("owed", Integer.toString(summary.owed())),
                    Map.entry("met", Integer.toString(summary.met())),
                    Map.entry("partial", Integer.toString(summary.partial())),
                    Map.entry("missed", Integer.toString(summary.missed())),
                    Map.entry("pending", Integer.toString(summary.pending())),
                    Map.entry("shielded", Render.pluralise(summary.shieldedDays(), "day")),
                    Map.entry("rate", Render.bar(summary.rate()) + " " + Render.percent(summary.rate())))));
            if (byHitch) {
                ctx.out("");
                ctx.out(Render.renderTable(report.byHitch(), summaryColumns()));
            }
            if (shape) {
                ctx.out("");
                ctx.out(Render.renderTable(report.byRole(), roleColumns()));
                ctx.out("");
                String busiest = report.busiestHour().isPresent()
                        ? Texts.padStart(Integer.toString(report.busiestHour().getAsInt()), 2, '0') + ":00"
                        : "nothing recorded";
                ctx.out(Render.keyValues(
                        List.of(Map.entry("trend", report.trend().describe()), Map.entry("busiest hour", busiest))));
            }
        }
    }

    @Command(name = "open", description = "what is still open in a window")
    static final class ReportOpen extends ContextCommand {

        @Parameters(index = "0", paramLabel = "<memberId>")
        private String memberId;

        @Option(names = "--window", required = true, paramLabel = "<window>", description = "window to look at")
        private String window;

        ReportOpen(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            List<ObligationResult> open = new AdherenceService(ctx.ports()).open(Ids.crewMemberId(memberId), window);
            ctx.out(Render.renderTable(open, resultColumns()));
        }
    }

    @Command(name = "streak", description = "the run on one habit")
    static final class ReportStreak extends ContextCommand {

        @Parameters(index = "0", paramLabel = "<memberId>")
        private String memberId;

        @Parameters(index = "1", paramLabel = "<habitId>")
        private String habitId;

        @Option(names = "--window", required = true, paramLabel = "<window>", description = "window to walk")
        private String window;

        ReportStreak(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            StreakSummary summary =
                    new AdherenceService(ctx.ports()).streak(Ids.crewMemberId(memberId), Ids.habitId(habitId), window);
            ctx.out(Render.keyValues(List.of(
                    Map.entry("current", Integer.toString(summary.current())),
                    Map.entry("longest", Integer.toString(summary.longest())),
                    Map.entry("shielded", Integer.toString(summary.shielded())),
                    Map.entry("last met", dayOrDash(summary.lastMetOn())),
                    Map.entry("broken on", dayOrDash(summary.brokenOn())))));
        }

        private static String dayOrDash(Optional<CivilDate> day) {
            return day.map(CivilDate::toString).orElse("-");
        }
    }

    @Command(name = "nudges", description = "what the planner would send")
    static final class ReportNudges extends ContextCommand {

        @Parameters(index = "0", paramLabel = "<memberId>")
        private String memberId;

        @Option(names = "--window", required = true, paramLabel = "<window>", description = "window to plan over")
        private String window;

        ReportNudges(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            List<PlannedNudge> planned = new NudgesService(ctx.ports()).plan(Ids.crewMemberId(memberId), window);
            List<Column<PlannedNudge>> columns = List.of(
                    Column.left("day", row -> row.onDate().toString()),
                    Column.left("at", row -> Zones.formatInstant(row.at())),
                    Column.left("why", row -> row.reason().wire()),
                    Column.left("habit", PlannedNudge::habitId));
            ctx.out(Render.renderTable(planned, columns));
        }
    }

    private static List<Column<RoleSplit>> roleColumns() {
        return List.of(
                Column.left("role", row -> row.role().wire()),
                Column.right("owed", row -> Integer.toString(row.owed())),
                Column.right("met", row -> Integer.toString(row.met())),
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
