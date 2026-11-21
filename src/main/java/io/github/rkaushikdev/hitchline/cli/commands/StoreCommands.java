package io.github.rkaushikdev.hitchline.cli.commands;

import io.github.rkaushikdev.hitchline.cli.Context;
import io.github.rkaushikdev.hitchline.cli.Render;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.time.Zones;
import io.github.rkaushikdev.hitchline.infra.store.Backup;
import io.github.rkaushikdev.hitchline.infra.store.Compaction;
import io.github.rkaushikdev.hitchline.infra.store.CompactionReport;
import io.github.rkaushikdev.hitchline.infra.store.Journal;
import io.github.rkaushikdev.hitchline.infra.store.RestoreReport;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Store maintenance. These commands open the journal directly rather than going through the
 * repositories, because they are about the file rather than about what is in it.
 */
public final class StoreCommands {

    private StoreCommands() {}

    /** The {@code store} commands. */
    public static CommandLine store(Supplier<Context> context) {
        return Arguments.group(
                "store",
                "look after the journal on disk",
                new Status(context),
                new Compact(context),
                new TakeBackup(context),
                new Restore(context));
    }

    /** The journal {@code given} names, or the configured one when the store is a file. */
    private static Path pathOf(Context ctx, String given) {
        if (given == null && ctx.config().store() != StoreKind.FILE) {
            throw ValidationException.field("path", "is needed when the store is not a file");
        }
        return Path.of(given == null ? ctx.config().storePath() : given);
    }

    @Command(name = "status", description = "say what the journal holds and whether it is worth compacting")
    static final class Status extends ContextCommand {

        @Option(names = "--path", paramLabel = "<path>", description = "journal to look at")
        private String path;

        Status(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            try (Journal journal = Journal.open(pathOf(ctx, path))) {
                CompactionReport report = Compaction.estimate(journal);
                ctx.out(Render.keyValues(List.of(
                        Map.entry("path", journal.path().toString()),
                        Map.entry("entries", Integer.toString(report.before())),
                        Map.entry("live", Integer.toString(report.after())),
                        Map.entry("dead", Integer.toString(report.dropped())),
                        Map.entry("unreadable", Integer.toString(report.skipped())),
                        Map.entry("compact", report.worthCompacting() ? "worth doing" : "not yet"))));
            }
        }
    }

    @Command(name = "compact", description = "drop the lines the store would not load")
    static final class Compact extends ContextCommand {

        @Option(names = "--path", paramLabel = "<path>", description = "journal to compact")
        private String path;

        Compact(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            try (Journal journal = Journal.open(pathOf(ctx, path))) {
                CompactionReport report = Compaction.compact(journal);
                ctx.out("compacted " + report.before() + " entries down to " + report.after());
            }
        }
    }

    @Command(name = "backup", description = "write a backup of the journal")
    static final class TakeBackup extends ContextCommand {

        @Option(names = "--path", paramLabel = "<path>", description = "journal to back up")
        private String path;

        @Parameters(index = "0", paramLabel = "<file>")
        private String file;

        TakeBackup(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            try (Journal journal = Journal.open(pathOf(ctx, path))) {
                Backup backup = Backup.take(
                        journal, Zones.formatInstant(ctx.ports().clock().now()));
                backup.write(Path.of(file));
                ctx.out(backup.describe());
            }
        }
    }

    @Command(name = "restore", description = "write a backup back into a journal", showDefaultValues = true)
    static final class Restore extends ContextCommand {

        @Option(names = "--path", paramLabel = "<path>", description = "journal to write into")
        private String path;

        @Parameters(index = "0", paramLabel = "<file>")
        private String file;

        @Option(names = "--force", description = "write over a journal that already holds something")
        private boolean force;

        Restore(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            Backup backup = Backup.read(Path.of(file));
            try (Journal journal = Journal.open(pathOf(ctx, path))) {
                RestoreReport report = backup.restore(journal, force);
                ctx.out("restored " + Render.pluralise(report.restored(), "entry", "entries") + " from " + file);
            }
        }
    }
}
