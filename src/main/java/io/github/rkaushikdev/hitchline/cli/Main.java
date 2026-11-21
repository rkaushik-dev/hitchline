package io.github.rkaushikdev.hitchline.cli;

import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.cli.commands.ConfigCommands;
import io.github.rkaushikdev.hitchline.cli.commands.CrewCommands;
import io.github.rkaushikdev.hitchline.cli.commands.DocsCommands;
import io.github.rkaushikdev.hitchline.cli.commands.DoctorCommands;
import io.github.rkaushikdev.hitchline.cli.commands.ExportCommands;
import io.github.rkaushikdev.hitchline.cli.commands.HabitCommands;
import io.github.rkaushikdev.hitchline.cli.commands.MemberCommands;
import io.github.rkaushikdev.hitchline.cli.commands.ReportCommands;
import io.github.rkaushikdev.hitchline.cli.commands.StoreCommands;
import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.http.Api;
import io.github.rkaushikdev.hitchline.infra.store.Stores;
import java.io.PrintWriter;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.UnmatchedArgumentException;

/**
 * The operator command line. It opens the same store the server does, which is the point: a
 * coordinator can fix a roster from a terminal while the api is running against the same journal.
 *
 * <p>Words are read the way operators expect: a repeated option keeps its last value, an option's
 * value is the next word even when that word starts with a dash, and {@code @file} words are left
 * alone. A command line that cannot be read (an unknown command or option, a missing required
 * option, a group named without one of its commands) exits {@link Context#EXIT_FAILED} after the
 * problem and the usage of the command it was meant for. {@link Context#EXIT_MISUSED} is kept for
 * a value the domain refused.
 */
public final class Main {

    /** The root of the command tree. */
    @Command(
            name = "hitchline",
            description = "adherence tracking for crews working rotational hitches",
            version = Api.VERSION,
            footer = {
                "",
                "Examples:",
                "  hitchline member add --name \"Ada Vasquez\" --pattern 14/14 --anchor 2025-01-06",
                "    --home Europe/Lisbon --site Europe/Oslo",
                "  hitchline habit add mem_... --name \"Twenty minutes\" --cadence 1/onshift-day",
                "  hitchline report adherence mem_... --window 2025-01-06..2025-01-19 --shape",
                "  hitchline store status",
                "",
                "A window is written 2025-01-06..2025-01-19 and both ends are included."
            })
    static final class Program {

        @Option(
                names = {"-V", "--version"},
                versionHelp = true,
                description = "output the version number")
        private boolean versionRequested;
    }

    private Main() {}

    /**
     * The command tree. Each command asks {@code context} for its context when it runs; help and
     * usage text go to that context's output and failure lines.
     */
    public static CommandLine buildProgram(Supplier<Context> context) {
        CommandLine program = new CommandLine(new Program());
        program.addSubcommand(MemberCommands.member(context));
        program.addSubcommand(HabitCommands.habit(context));
        program.addSubcommand(HabitCommands.checkIn(context));
        program.addSubcommand(ReportCommands.report(context));
        program.addSubcommand(StoreCommands.store(context));
        program.addSubcommand(CrewCommands.crew(context));
        program.addSubcommand(CrewCommands.demo(context));
        program.addSubcommand(ExportCommands.export(context));
        program.addSubcommand(DoctorCommands.doctor(context));
        program.addSubcommand(DocsCommands.docs(context));
        program.addSubcommand(ConfigCommands.config(context));
        program.addSubcommand(new HelpCommand());
        addHelpOption(program);

        program.setExpandAtFiles(false);
        program.setOverwrittenOptionsAllowed(true);
        program.setAllowOptionsAsOptionParameters(true);
        program.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
        program.setOut(new PrintWriter(new LineWriter(line -> context.get().out(line)), true));
        program.setErr(new PrintWriter(new LineWriter(line -> context.get().fail(line)), true));
        return program;
    }

    private static void addHelpOption(CommandLine command) {
        CommandSpec spec = command.getCommandSpec();
        if (!spec.helpCommand() && spec.findOption("--help") == null) {
            spec.addOption(OptionSpec.builder("-h", "--help")
                    .usageHelp(true)
                    .description("display help for command")
                    .build());
        }
        command.getSubcommands().values().forEach(Main::addHelpOption);
    }

    /** Runs one command line against {@code context} and answers the exit code. */
    public static int run(Context context, String... args) {
        return run(() -> context, args);
    }

    /**
     * Runs one command line and answers the exit code. Help and the version exit
     * {@link Context#EXIT_OK}; a group named without one of its commands is a usage error. Anything a
     * command throws that is not a domain failure reaches the caller.
     */
    public static int run(Supplier<Context> context, String... args) {
        CommandLine program = buildProgram(context);
        try {
            return dispatch(program, args);
        } finally {
            program.getOut().close();
            program.getErr().close();
        }
    }

    /**
     * Reads the configuration's store, runs one command line against it on the console, and closes
     * the store whatever happened.
     */
    public static int runConfigured(Config config, String... args) {
        Ports store = Stores.openStore(config);
        try {
            return run(() -> Context.consoleContext(store, config), args);
        } finally {
            Stores.closeStore(store);
        }
    }

    private static int dispatch(CommandLine program, String... args) {
        ParseResult parsed;
        try {
            parsed = program.parseArgs(args);
            if (CommandLine.printHelpIfRequested(parsed)) {
                return Context.EXIT_OK;
            }
        } catch (ParameterException misuse) {
            return unreadable(misuse);
        }
        ParseResult named = parsed;
        while (named.hasSubcommand()) {
            named = named.subcommand();
        }
        if (named.commandSpec().userObject() instanceof Action action) {
            return action.call();
        }
        return unreadable(new ParameterException(named.commandSpec().commandLine(), "Missing required subcommand"));
    }

    private static int unreadable(ParameterException misuse) {
        CommandLine where = misuse.getCommandLine();
        PrintWriter err = where.getErr();
        err.println(misuse.getMessage());
        if (!UnmatchedArgumentException.printSuggestions(misuse, err)) {
            where.usage(err);
        }
        err.flush();
        return Context.EXIT_FAILED;
    }
}
