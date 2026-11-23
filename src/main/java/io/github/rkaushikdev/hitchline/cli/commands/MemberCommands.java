package io.github.rkaushikdev.hitchline.cli.commands;

import io.github.rkaushikdev.hitchline.app.RosterService;
import io.github.rkaushikdev.hitchline.app.RosterService.HoldCommand;
import io.github.rkaushikdev.hitchline.app.RosterService.RosterDay;
import io.github.rkaushikdev.hitchline.cli.Column;
import io.github.rkaushikdev.hitchline.cli.Context;
import io.github.rkaushikdev.hitchline.cli.Render;
import io.github.rkaushikdev.hitchline.domain.IdKind;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.OverrideReason;
import io.github.rkaushikdev.hitchline.domain.rotation.RosterOverride;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationAssignment;
import io.github.rkaushikdev.hitchline.domain.rotation.RotationPattern;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Member and roster commands. These are the ones a coordinator runs while setting a crew up, so they
 * take patterns and dates the way the rota is written rather than as ids and instants.
 */
public final class MemberCommands {

    private MemberCommands() {}

    /** The {@code member} commands. */
    public static CommandLine member(Supplier<Context> context) {
        return Arguments.group(
                "member",
                "add and read crew members",
                new AddMember(context),
                new ListMembers(context),
                new PrintRoster(context),
                new HoldRoster(context));
    }

    @Command(name = "add", description = "add a crew member on a rotation", showDefaultValues = true)
    static final class AddMember extends ContextCommand {

        @Option(names = "--name", required = true, paramLabel = "<name>", description = "display name")
        private String name;

        @Option(
                names = "--pattern",
                required = true,
                paramLabel = "<pattern>",
                description = "rotation pattern such as 14/14")
        private String pattern;

        @Option(
                names = "--anchor",
                required = true,
                paramLabel = "<date>",
                description = "the date the cycle starts on")
        private String anchor;

        @Option(names = "--home", paramLabel = "<zone>", description = "home timezone", defaultValue = "UTC")
        private String home;

        @Option(names = "--site", paramLabel = "<zone>", description = "site timezone", defaultValue = "UTC")
        private String site;

        @Option(
                names = "--phase",
                paramLabel = "<days>",
                description = "days into the cycle at the anchor",
                defaultValue = "0")
        private String phase;

        AddMember(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            String id = Ids.crewMemberId(ctx.ports().ids().next(IdKind.CREW_MEMBER));
            CivilDate joinedOn = Zones.civilDateIn(site, ctx.ports().clock().now());
            RotationAssignment rotation = RotationAssignment.of(
                    RotationPattern.parse(pattern),
                    CivilDate.parse(anchor),
                    Arguments.wholeNumber(phase, "phase", "must be a whole number of days into the cycle"));
            CrewMember member = CrewMember.of(id, name, home, site, rotation, joinedOn);
            ctx.ports().members().save(member);
            ctx.out(member.id() + "  " + member.displayName());
        }
    }

    @Command(name = "list", description = "list the crew members this store knows")
    static final class ListMembers extends ContextCommand {

        ListMembers(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            List<Column<CrewMember>> columns = List.of(
                    Column.left("id", CrewMember::id),
                    Column.left("name", CrewMember::displayName),
                    Column.left("site", CrewMember::siteZone),
                    Column.left("rota", MemberCommands::rotaOf),
                    Column.left("status", row -> row.status().wire()));
            ctx.out(Render.renderTable(ctx.ports().members().all(), columns));
        }
    }

    /** The rota by the name the crewing office uses, or written out when it has none. */
    static String rotaOf(CrewMember member) {
        RotationPattern pattern = member.rotation().pattern();
        return pattern.knownAs().orElseGet(pattern::format);
    }

    @Command(name = "roster", description = "print the roster over a window")
    static final class PrintRoster extends ContextCommand {

        @Parameters(index = "0", paramLabel = "<memberId>")
        private String memberId;

        @Option(
                names = "--window",
                required = true,
                paramLabel = "<window>",
                description = "window such as 2025-01-06..2025-01-19")
        private String window;

        PrintRoster(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            List<RosterDay> days = new RosterService(ctx.ports()).daysFor(Ids.crewMemberId(memberId), window);
            List<Column<RosterDay>> columns = List.of(
                    Column.left("date", day -> day.date().toString()),
                    Column.left("role", day -> day.role().wire()),
                    Column.left("from", day -> day.source().wire()),
                    Column.left("zone", RosterDay::zone));
            ctx.out(Render.renderTable(days, columns));
        }
    }

    @Command(name = "hold", description = "hold somebody over or send them home", showDefaultValues = true)
    static final class HoldRoster extends ContextCommand {

        @Parameters(index = "0", paramLabel = "<memberId>")
        private String memberId;

        @Option(names = "--window", required = true, paramLabel = "<window>", description = "the days to change")
        private String window;

        @Option(
                names = "--role",
                required = true,
                paramLabel = "<role>",
                description = "onshift, offshift, travel or standby")
        private String role;

        @Option(names = "--reason", required = true, paramLabel = "<reason>", description = "why the roster changed")
        private String reason;

        @Option(
                names = "--note",
                paramLabel = "<note>",
                description = "a line for whoever reads it later",
                defaultValue = "")
        private String note;

        HoldRoster(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            HoldCommand command = new HoldCommand(
                    Ids.crewMemberId(memberId),
                    window,
                    Arguments.word(DayRole.class, "role", role),
                    Arguments.word(OverrideReason.class, "reason", reason),
                    Optional.of(note));
            RosterOverride entry = new RosterService(ctx.ports()).hold(command);
            ctx.out("held " + memberId + " " + window + " as " + entry.role().wire());
        }
    }
}
