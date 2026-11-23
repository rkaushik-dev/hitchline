package io.github.rkaushikdev.hitchline.cli.commands;

import io.github.rkaushikdev.hitchline.app.CheckInsService;
import io.github.rkaushikdev.hitchline.app.CheckInsService.RecordCheckInCommand;
import io.github.rkaushikdev.hitchline.app.CheckInsService.RecordResult;
import io.github.rkaushikdev.hitchline.app.HabitsService;
import io.github.rkaushikdev.hitchline.app.HabitsService.CreateHabitCommand;
import io.github.rkaushikdev.hitchline.cli.Column;
import io.github.rkaushikdev.hitchline.cli.Context;
import io.github.rkaushikdev.hitchline.cli.Render;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckInSource;
import io.github.rkaushikdev.hitchline.domain.checkin.ResolvedCheckIn;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitStatus;
import io.github.rkaushikdev.hitchline.domain.habit.HabitTarget;
import io.github.rkaushikdev.hitchline.domain.time.DateRange;
import io.github.rkaushikdev.hitchline.util.Numbers;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Habit and check in commands. A supervisor uses these to fix things up after the fact, which is why
 * the check in command takes a time and a source rather than assuming now and the app.
 */
public final class HabitCommands {

    private HabitCommands() {}

    /** The {@code habit} commands. */
    public static CommandLine habit(Supplier<Context> context) {
        return Arguments.group(
                "habit",
                "add, read and change habits",
                new AddHabit(context),
                new ListHabits(context),
                new PauseHabit(context),
                new ResumeHabit(context));
    }

    /** The {@code checkin} commands. */
    public static CommandLine checkIn(Supplier<Context> context) {
        return Arguments.group(
                "checkin",
                "record and read check ins",
                new RecordCheckIn(context),
                new ListCheckIns(context),
                new VoidCheckIn(context));
    }

    @Command(name = "add", description = "give somebody a habit", showDefaultValues = true)
    static final class AddHabit extends ContextCommand {

        @Parameters(index = "0", paramLabel = "<memberId>")
        private String memberId;

        @Option(names = "--name", required = true, paramLabel = "<name>", description = "what the habit is")
        private String name;

        @Option(
                names = "--cadence",
                required = true,
                paramLabel = "<cadence>",
                description = "how often, such as 1/onshift-day")
        private String cadence;

        @Option(
                names = "--target",
                paramLabel = "<target>",
                description = "what counts, such as \"at least 20 minutes\"")
        private String target;

        @Option(names = "--starts", paramLabel = "<date>", description = "the first day it asks for anything")
        private String starts;

        @Option(names = "--ends", paramLabel = "<date>", description = "the last day it asks for anything")
        private String ends;

        @Option(
                names = "--grace",
                paramLabel = "<days>",
                description = "days after the fact a check in still counts",
                defaultValue = "0")
        private String grace;

        AddHabit(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            CreateHabitCommand command = new CreateHabitCommand(
                    Ids.crewMemberId(memberId),
                    name,
                    cadence,
                    Optional.ofNullable(target),
                    Optional.ofNullable(starts),
                    Optional.ofNullable(ends),
                    List.of(),
                    OptionalInt.of(Arguments.wholeNumber(grace, "graceDays", "must be a whole number of days")));
            Habit created = new HabitsService(ctx.ports()).create(command);
            ctx.out(created.id() + "  " + created.name());
        }
    }

    @Command(name = "list", description = "list somebody habits")
    static final class ListHabits extends ContextCommand {

        @Parameters(index = "0", paramLabel = "<memberId>")
        private String memberId;

        ListHabits(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            List<Habit> habits = new HabitsService(ctx.ports()).listFor(Ids.crewMemberId(memberId));
            List<Column<Habit>> columns = List.of(
                    Column.left("id", Habit::id),
                    Column.left("name", Habit::name),
                    Column.left("cadence", row -> row.cadence().format()),
                    Column.left(
                            "target",
                            row -> row.target().map(HabitTarget::format).orElse("-")),
                    Column.left("status", row -> row.status().wire()));
            ctx.out(Render.renderTable(habits, columns));
        }
    }

    /** Moves a habit to one status and says where it ended up. */
    abstract static class MoveHabit extends ContextCommand {

        @Parameters(index = "0", paramLabel = "<habitId>")
        private String habitId;

        private final HabitStatus status;

        MoveHabit(Supplier<Context> context, HabitStatus status) {
            super(context);
            this.status = status;
        }

        @Override
        void run(Context ctx) {
            Habit moved = new HabitsService(ctx.ports()).moveTo(Ids.habitId(habitId), status);
            ctx.out(moved.id() + " is " + moved.status().wire());
        }
    }

    @Command(name = "pause", description = "stop a habit asking for anything")
    static final class PauseHabit extends MoveHabit {

        PauseHabit(Supplier<Context> context) {
            super(context, HabitStatus.PAUSED);
        }
    }

    @Command(name = "resume", description = "start it asking again")
    static final class ResumeHabit extends MoveHabit {

        ResumeHabit(Supplier<Context> context) {
            super(context, HabitStatus.ACTIVE);
        }
    }

    @Command(name = "record", description = "record that somebody did the thing", showDefaultValues = true)
    static final class RecordCheckIn extends ContextCommand {

        @Parameters(index = "0", paramLabel = "<habitId>")
        private String habitId;

        @Option(names = "--at", paramLabel = "<instant>", description = "when it happened, iso, defaults to now")
        private String at;

        @Option(names = "--reading", paramLabel = "<amount>", description = "how much, in the habit unit")
        private String reading;

        @Option(names = "--note", paramLabel = "<note>", description = "a line for whoever reads it later")
        private String note;

        @Option(
                names = "--source",
                paramLabel = "<source>",
                description = "app, watch, kiosk, import or supervisor",
                defaultValue = "supervisor")
        private String source;

        RecordCheckIn(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            RecordCheckInCommand command = new RecordCheckInCommand(
                    Ids.habitId(habitId),
                    Optional.ofNullable(at),
                    reading == null ? OptionalDouble.empty() : OptionalDouble.of(Numbers.parse(reading)),
                    Optional.ofNullable(note),
                    Optional.of(Arguments.word(CheckInSource.class, "source", source)),
                    Optional.empty());
            RecordResult result = new CheckInsService(ctx.ports()).record(command);
            ctx.out((result.accepted() ? "recorded " : "already had ")
                    + result.entry().id());
        }
    }

    @Command(name = "list", description = "list what somebody has recorded")
    static final class ListCheckIns extends ContextCommand {

        @Parameters(index = "0", paramLabel = "<memberId>")
        private String memberId;

        @Option(names = "--window", paramLabel = "<window>", description = "narrow it to a window")
        private String window;

        ListCheckIns(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            Optional<DateRange> narrowed = Optional.ofNullable(window).map(DateRange::parse);
            List<ResolvedCheckIn> found =
                    new CheckInsService(ctx.ports()).listFor(Ids.crewMemberId(memberId), narrowed);
            Set<String> repeats = ResolvedCheckIn.repeatTaps(found);
            ctx.out(Render.renderTable(found, columns(repeats)));
        }

        /** The listing, with a tap repeated within two minutes marked as not counted. */
        private static List<Column<ResolvedCheckIn>> columns(Set<String> repeats) {
            return List.of(
                    Column.left("id", row -> row.checkIn().id()),
                    Column.left("day", row -> row.day().date().toString()),
                    Column.left("role", row -> row.day().role().wire()),
                    Column.right("reading", row -> readingOf(row.checkIn())),
                    Column.left("source", row -> row.checkIn().source().wire()),
                    Column.left("counted", row -> repeats.contains(row.checkIn().id()) ? "repeat" : "yes"));
        }

        private static String readingOf(CheckIn entry) {
            return entry.reading().isPresent() ? Numbers.format(entry.reading().getAsDouble()) : "-";
        }
    }

    @Command(name = "void", description = "take a check in back out of the count")
    static final class VoidCheckIn extends ContextCommand {

        @Parameters(index = "0", paramLabel = "<checkInId>")
        private String checkInId;

        VoidCheckIn(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            CheckIn voided = new CheckInsService(ctx.ports()).voidEntry(Ids.checkInId(checkInId));
            ctx.out("voided " + voided.id());
        }
    }
}
