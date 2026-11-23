package io.github.rkaushikdev.hitchline.cli.commands;

import io.github.rkaushikdev.hitchline.app.CompareService;
import io.github.rkaushikdev.hitchline.app.CompareService.CrewComparison;
import io.github.rkaushikdev.hitchline.app.CompareService.CrewLine;
import io.github.rkaushikdev.hitchline.app.CrewService;
import io.github.rkaushikdev.hitchline.app.Seed;
import io.github.rkaushikdev.hitchline.app.Seed.SeedOptions;
import io.github.rkaushikdev.hitchline.app.Seed.SeedReport;
import io.github.rkaushikdev.hitchline.cli.Column;
import io.github.rkaushikdev.hitchline.cli.Context;
import io.github.rkaushikdev.hitchline.cli.Render;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.crew.Crew;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.crew.CrewRole;
import io.github.rkaushikdev.hitchline.domain.crew.Membership;
import io.github.rkaushikdev.hitchline.domain.crew.PersonsOnBoard;
import io.github.rkaushikdev.hitchline.util.Numbers;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Crew commands. A coordinator setting a rig up runs these once; a lead reads the comparison every
 * hitch.
 */
public final class CrewCommands {

    private CrewCommands() {}

    /** The {@code crew} commands. */
    public static CommandLine crew(Supplier<Context> context) {
        return Arguments.group(
                "crew",
                "crews, membership and comparison",
                new AddCrew(context),
                new Enrol(context),
                new ListCrewMembers(context),
                new Compare(context));
    }

    /** The {@code demo} commands. */
    public static CommandLine demo(Supplier<Context> context) {
        return Arguments.group("demo", "fill a store with something to look at", new SeedDemo(context));
    }

    @Command(name = "add", description = "start a crew at a site")
    static final class AddCrew extends ContextCommand {

        @Option(names = "--name", required = true, paramLabel = "<name>", description = "what the crew is called")
        private String name;

        @Option(names = "--site", required = true, paramLabel = "<site>", description = "the installation")
        private String site;

        @Option(names = "--zone", required = true, paramLabel = "<zone>", description = "the site timezone")
        private String zone;

        AddCrew(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            Crew created = new CrewService(ctx.ports()).create(name, site, zone);
            ctx.out(created.id() + "  " + created.name() + " at " + created.site());
        }
    }

    @Command(name = "enrol", description = "put somebody on a crew", showDefaultValues = true)
    static final class Enrol extends ContextCommand {

        @Parameters(index = "0", paramLabel = "<crewId>")
        private String crew;

        @Parameters(index = "1", paramLabel = "<memberId>")
        private String member;

        @Option(names = "--from", required = true, paramLabel = "<date>", description = "the first day they are on it")
        private String from;

        @Option(
                names = "--role",
                paramLabel = "<role>",
                description = "member, lead or coordinator",
                defaultValue = "member")
        private String role;

        Enrol(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            String crewId = Ids.crewId(crew);
            String memberId = Ids.crewMemberId(member);
            CrewRole chosen = Arguments.word(CrewRole.class, "role", role);
            Membership entry = new CrewService(ctx.ports()).enrol(crewId, memberId, from, Optional.of(chosen));
            ctx.out(entry.memberId() + " joined " + entry.crewId() + " as " + entry.role());
        }
    }

    @Command(name = "members", description = "who was on a crew on a date, or on board over a window")
    static final class ListCrewMembers extends ContextCommand {

        @Parameters(index = "0", paramLabel = "<crewId>")
        private String crew;

        @Option(names = "--on", paramLabel = "<date>", description = "the day to read it as")
        private String on;

        @Option(
                names = "--window",
                paramLabel = "<window>",
                description = "count who is on board each day of a window instead")
        private String window;

        @Option(names = "--beds", paramLabel = "<beds>", description = "flag the days more are on board than this")
        private String beds;

        ListCrewMembers(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            CrewService crews = new CrewService(ctx.ports());
            if ((on == null) == (window == null)) {
                throw ValidationException.field("on", "give either --on or --window");
            }
            if (on != null) {
                ctx.out(Render.renderTable(crews.membersOfCrew(Ids.crewId(crew), on), MEMBER_COLUMNS));
                return;
            }
            Optional<Integer> limit = Optional.ofNullable(beds)
                    .map(text -> Arguments.wholeNumber(text, "beds", "must be a whole number of beds"));
            List<Column<PersonsOnBoard>> columns = List.of(
                    Column.left("date", row -> row.date().toString()),
                    Column.right("on board", row -> Integer.toString(row.onBoard())),
                    Column.right("travelling", row -> Integer.toString(row.travelling())),
                    Column.right("leads", row -> Integer.toString(row.leads())),
                    Column.left("flag", row -> String.join(", ", row.problems(limit))));
            ctx.out(Render.renderTable(crews.personsOnBoard(Ids.crewId(crew), window), columns));
        }
    }

    private static final List<Column<CrewLine>> LINE_COLUMNS = List.of(
            Column.left("name", CrewLine::displayName),
            Column.right("owed", row -> Integer.toString(row.owed())),
            Column.right("met", row -> Integer.toString(row.met())),
            Column.right("partial", row -> Integer.toString(row.partial())),
            Column.right("missed", row -> Integer.toString(row.missed())),
            Column.right("rate", row -> Render.percent(row.rate())),
            Column.right("hidden", row -> Integer.toString(row.hidden())));

    private static final List<Column<CrewMember>> MEMBER_COLUMNS = List.of(
            Column.left("id", CrewMember::id),
            Column.left("name", CrewMember::displayName),
            Column.left("home", CrewMember::homeZone),
            Column.left("joined", row -> row.joinedOn().toString()));

    @Command(name = "compare", description = "read the crew side by side as one member would see it")
    static final class Compare extends ContextCommand {

        @Parameters(index = "0", paramLabel = "<crewId>")
        private String crew;

        @Parameters(index = "1", paramLabel = "<viewerId>")
        private String viewer;

        @Option(names = "--window", required = true, paramLabel = "<window>", description = "window to score")
        private String window;

        @Option(names = "--on", required = true, paramLabel = "<date>", description = "the day to read membership as")
        private String on;

        Compare(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            CrewComparison report =
                    new CompareService(ctx.ports()).crew(Ids.crewMemberId(viewer), Ids.crewId(crew), window, on);
            ctx.out(Render.renderTable(report.lines(), LINE_COLUMNS));
            ctx.out("");
            ctx.out(Render.keyValues(
                    List.of(Map.entry("crew", Render.bar(report.rate()) + " " + Render.percent(report.rate())))));
        }
    }

    @Command(
            name = "seed",
            description = "write a crew, their habits and a fortnight of check ins",
            showDefaultValues = true)
    static final class SeedDemo extends ContextCommand {

        @Option(names = "--from", paramLabel = "<date>", description = "the first day of the history")
        private String from;

        @Option(names = "--days", paramLabel = "<days>", description = "how many days to write", defaultValue = "28")
        private String days;

        @Option(
                names = "--adherence",
                paramLabel = "<share>",
                description = "roughly how much of it gets done",
                defaultValue = "0.8")
        private String adherence;

        SeedDemo(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            SeedOptions options = SeedOptions.DEFAULT
                    .withDays(Arguments.wholeNumber(days, "days", "must be a whole number of days"))
                    .withAdherence(Numbers.parse(adherence));
            if (from != null) {
                options = options.withFrom(from);
            }
            SeedReport report = Seed.seedDemo(ctx.ports(), options);
            ctx.out(Render.keyValues(List.of(
                    Map.entry("crew", report.crewId()),
                    Map.entry("members", Integer.toString(report.members())),
                    Map.entry("habits", Integer.toString(report.habits())),
                    Map.entry("check ins", Integer.toString(report.checkIns())),
                    Map.entry("from", report.from().toString()),
                    Map.entry("to", report.to().toString()))));
        }
    }
}
