package io.github.rkaushikdev.hitchline.cli.commands;

import io.github.rkaushikdev.hitchline.app.RosterService;
import io.github.rkaushikdev.hitchline.app.RosterService.HoldCommand;
import io.github.rkaushikdev.hitchline.app.RosterService.RosterDay;
import io.github.rkaushikdev.hitchline.cli.Column;
import io.github.rkaushikdev.hitchline.cli.Context;
import io.github.rkaushikdev.hitchline.cli.Render;
import io.github.rkaushikdev.hitchline.domain.IdKind;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.domain.rotation.DayRole;
import io.github.rkaushikdev.hitchline.domain.rotation.DutyBreach;
import io.github.rkaushikdev.hitchline.domain.rotation.DutyLimits;
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

        @Option(names = "--anchor", paramLabel = "<date>", description = "the date the cycle starts on")
        private String anchor;

        @Option(
                names = "--crew-change",
                paramLabel = "<date>",
                description = "a day they fly out, in place of an anchor and phase")
        private String crewChange;

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
            CrewMember member = CrewMember.of(id, name, home, site, rotation(), joinedOn);
            ctx.ports().members().save(member);
            ctx.out(member.id() + "  " + member.displayName());
        }

        /** Pinned on the anchor and phase, or on the crew change day; exactly one has to be given. */
        private RotationAssignment rotation() {
            if ((anchor == null) == (crewChange == null)) {
                throw ValidationException.field("anchor", "give either --anchor or --crew-change");
            }
            RotationPattern parsed = RotationPattern.parse(pattern);
            if (crewChange != null) {
                return RotationAssignment.fromCrewChange(parsed, CivilDate.parse(crewChange));
            }
            return RotationAssignment.of(
                    parsed,
                    CivilDate.parse(anchor),
                    Arguments.wholeNumber(phase, "phase", "must be a whole number of days into the cycle"));
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

        @Option(names = "--check", description = "check the trips and breaks against the duty limits")
        private boolean check;

        @Option(
                names = "--max-trip",
                paramLabel = "<days>",
                description = "the longest a trip may run",
                defaultValue = "21")
        private String maxTrip;

        @Option(
                names = "--min-break",
                paramLabel = "<days>",
                description = "the shortest a break at home may be",
                defaultValue = "7")
        private String minBreak;

        PrintRoster(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            String id = Ids.crewMemberId(memberId);
            RosterService roster = new RosterService(ctx.ports());
            ctx.out(Render.renderTable(roster.daysFor(id, window), DAY_COLUMNS));
            if (check) {
                DutyLimits limits = DutyLimits.of(
                        Arguments.wholeNumber(maxTrip, "maxTripDays", "must be a whole number of days"),
                        Arguments.wholeNumber(minBreak, "minBreakDays", "must be a whole number of days"));
                List<DutyBreach> breaches = roster.dutyBreaches(id, window, limits);
                ctx.out("");
                if (breaches.isEmpty()) {
                    ctx.out("within the duty limits");
                }
                breaches.forEach(breach -> ctx.out(breach.describe()));
            }
        }
    }

    private static final List<Column<RosterDay>> DAY_COLUMNS = List.of(
            Column.left("date", day -> day.date().toString()),
            Column.left("role", day -> day.role().wire()),
            Column.left("from", day -> day.source().wire()),
            Column.left("zone", RosterDay::zone));

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

        @Option(names = "--past-limits", description = "store the hold even when it runs somebody past the duty limits")
        private boolean pastLimits;

        HoldRoster(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            HoldCommand plain = new HoldCommand(
                    Ids.crewMemberId(memberId),
                    window,
                    Arguments.word(DayRole.class, "role", role),
                    Arguments.word(OverrideReason.class, "reason", reason),
                    Optional.of(note));
            HoldCommand command = pastLimits ? plain : plain.withinLimits(DutyLimits.DEFAULT);
            RosterOverride entry = new RosterService(ctx.ports()).hold(command);
            ctx.out("held " + memberId + " " + window + " as " + entry.role().wire());
        }
    }
}
