package io.github.rkaushikdev.hitchline.cli.commands;

import io.github.rkaushikdev.hitchline.app.AdherenceService;
import io.github.rkaushikdev.hitchline.app.CheckInsService;
import io.github.rkaushikdev.hitchline.app.LoadedMember;
import io.github.rkaushikdev.hitchline.app.MemberLoader;
import io.github.rkaushikdev.hitchline.app.RosterService;
import io.github.rkaushikdev.hitchline.cli.Context;
import io.github.rkaushikdev.hitchline.cli.Render;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.schedule.Expansion;
import io.github.rkaushikdev.hitchline.domain.schedule.Obligation;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.export.CsvExport;
import io.github.rkaushikdev.hitchline.export.IcsExport;
import io.github.rkaushikdev.hitchline.export.IcsExport.CalendarOptions;
import io.github.rkaushikdev.hitchline.util.Texts;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Export commands. Everything goes to standard output unless a file is named, so the usual pipe
 * into a spreadsheet or a mail attachment works without the tool knowing anything about either.
 */
public final class ExportCommands {

    private ExportCommands() {}

    /** The {@code export} commands. */
    public static CommandLine export(Supplier<Context> context) {
        return Arguments.group(
                "export",
                "write records out for somebody else",
                new ExportCheckIns(context),
                new ExportRoster(context),
                new ExportAdherence(context),
                new ExportCalendar(context));
    }

    /** What every export takes: whose records, the window, and where the output goes. */
    abstract static class Export extends ContextCommand {

        @Parameters(index = "0", paramLabel = "<memberId>")
        String memberId;

        @Option(names = "--window", required = true, paramLabel = "<window>", description = "window to export")
        String window;

        @Option(names = "--out", paramLabel = "<file>", description = "write to a file rather than standard output")
        String out;

        Export(Supplier<Context> context) {
            super(context);
        }

        /**
         * Prints {@code body}, or writes it to the named file and says how many lines went there,
         * counting the line feeds.
         */
        void deliver(Context ctx, String body) {
            if (out == null) {
                ctx.out(Texts.trimEnd(body));
                return;
            }
            Arguments.writeFile(out, body);
            ctx.out("wrote " + Render.pluralise(Texts.split(body, "\n").size() - 1, "line") + " to " + out);
        }
    }

    @Command(name = "checkins", description = "every check in in a window as csv")
    static final class ExportCheckIns extends Export {

        ExportCheckIns(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            var rows = new CheckInsService(ctx.ports())
                    .listFor(Ids.crewMemberId(memberId), Optional.of(DateRange.parse(window)));
            deliver(ctx, CsvExport.checkInsToCsv(rows));
        }
    }

    @Command(name = "roster", description = "the roster over a window as csv")
    static final class ExportRoster extends Export {

        ExportRoster(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            var days = new RosterService(ctx.ports()).daysFor(Ids.crewMemberId(memberId), window);
            deliver(ctx, CsvExport.rosterToCsv(days));
        }
    }

    @Command(name = "adherence", description = "every obligation and how it went as csv")
    static final class ExportAdherence extends Export {

        ExportAdherence(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            var report = new AdherenceService(ctx.ports()).report(Ids.crewMemberId(memberId), window);
            deliver(ctx, CsvExport.resultsToCsv(report.results()));
        }
    }

    @Command(name = "calendar", description = "what is owed in a window as an ics calendar")
    static final class ExportCalendar extends Export {

        ExportCalendar(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            LoadedMember loaded = new MemberLoader(ctx.ports()).load(Ids.crewMemberId(memberId));
            DateRange range = DateRange.parse(window);
            List<Obligation> obligations = loaded.habits().stream()
                    .flatMap(habit -> Expansion.of(loaded.roster(), habit, range).expand().stream())
                    .toList();
            String body = IcsExport.obligationsToCalendar(
                    loaded.habits(),
                    obligations,
                    CalendarOptions.of(
                            loaded.roster().member().displayName() + " hitchline",
                            ctx.ports().clock().now()));
            deliver(ctx, body);
        }
    }
}
