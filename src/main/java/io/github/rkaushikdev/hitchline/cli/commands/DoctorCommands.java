package io.github.rkaushikdev.hitchline.cli.commands;

import io.github.rkaushikdev.hitchline.cli.Column;
import io.github.rkaushikdev.hitchline.cli.Context;
import io.github.rkaushikdev.hitchline.cli.Render;
import io.github.rkaushikdev.hitchline.config.StoreKind;
import io.github.rkaushikdev.hitchline.domain.checkin.ChainCheck;
import io.github.rkaushikdev.hitchline.domain.checkin.ChainLink;
import io.github.rkaushikdev.hitchline.domain.checkin.CheckIn;
import io.github.rkaushikdev.hitchline.domain.checkin.Integrity;
import io.github.rkaushikdev.hitchline.domain.crew.CrewMember;
import io.github.rkaushikdev.hitchline.http.Readiness;
import io.github.rkaushikdev.hitchline.http.Readiness.CheckResult;
import io.github.rkaushikdev.hitchline.http.Readiness.CheckState;
import io.github.rkaushikdev.hitchline.infra.store.Compaction;
import io.github.rkaushikdev.hitchline.infra.store.CompactionReport;
import io.github.rkaushikdev.hitchline.infra.store.Journal;
import io.github.rkaushikdev.hitchline.infra.store.LockOwner;
import io.github.rkaushikdev.hitchline.infra.store.Locks;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * One command that answers "is this thing all right". It is what somebody runs before they ring
 * anybody, so it prints everything it looked at and not only the failures.
 */
public final class DoctorCommands {

    private DoctorCommands() {}

    /** The {@code doctor} commands. */
    public static CommandLine doctor(Supplier<Context> context) {
        return Arguments.group("doctor", "check the store and the process over", new RunChecks(context));
    }

    @Command(name = "run", description = "run every check and print what each one found")
    static final class RunChecks extends ContextCommand {

        @Option(
                names = "--path",
                paramLabel = "<path>",
                description = "journal to look at, defaults to the configured one")
        private String path;

        RunChecks(Supplier<Context> context) {
            super(context);
        }

        @Override
        void run(Context ctx) {
            List<CheckResult> checks =
                    new ArrayList<>(Readiness.read(ctx.ports(), ctx.config()).checks());

            if (ctx.config().store() == StoreKind.FILE || path != null) {
                Path journalPath = Path.of(path == null ? ctx.config().storePath() : path);
                checks.addAll(journalChecks(journalPath));
                Optional<LockOwner> holder = Locks.readLock(journalPath);
                checks.add(new CheckResult(
                        "lock",
                        CheckState.PASS,
                        holder.map(owner -> "held by pid " + owner.pid() + " on " + owner.host())
                                .orElse("nobody holds the writer lock"),
                        0));
            }

            List<CrewMember> members = ctx.ports().members().all();
            int entries = 0;
            for (CrewMember member : members) {
                List<CheckIn> rows = ctx.ports().checkIns().byMember(member.id());
                entries += rows.size();
                ChainCheck check = Integrity.verifyChain(
                        rows,
                        Integrity.chainOf(rows).stream().map(ChainLink::digest).toList());
                if (!check.ok()) {
                    checks.add(new CheckResult("ledger", CheckState.FAIL, check.describe(), 0));
                }
            }
            checks.add(new CheckResult(
                    "ledger", CheckState.PASS, entries + " check ins across " + members.size() + " members", 0));

            List<Column<CheckResult>> columns = List.of(
                    Column.left("check", CheckResult::name),
                    Column.left("state", row -> row.state().wire()),
                    Column.left("detail", CheckResult::detail));
            ctx.out(Render.renderTable(checks, columns));
            if (checks.stream().anyMatch(check -> check.state() == CheckState.FAIL)) {
                ctx.fail("one or more checks failed");
            }
        }

        private static List<CheckResult> journalChecks(Path journalPath) {
            try (Journal journal = Journal.open(journalPath)) {
                CompactionReport report = Compaction.estimate(journal);
                boolean unreadable = report.skipped() > 0;
                boolean worth = report.worthCompacting();
                return List.of(
                        new CheckResult(
                                "journal",
                                unreadable ? CheckState.WARN : CheckState.PASS,
                                unreadable
                                        ? report.skipped() + " lines could not be read"
                                        : report.before() + " entries, " + report.after() + " live",
                                0),
                        new CheckResult(
                                "compaction",
                                worth ? CheckState.WARN : CheckState.PASS,
                                worth ? report.dropped() + " dead entries, worth compacting" : "nothing worth dropping",
                                0));
            }
        }
    }
}
