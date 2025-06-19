import { Command } from 'commander';
import { readiness } from '../../http/readiness.js';
import { openJournal } from '../../infra/store/journal.js';
import { estimate, worthCompacting } from '../../infra/store/compact.js';
import { readLock } from '../../infra/store/lock.js';
import { chainOf, verifyChain, describeCheck } from '../../domain/checkin/integrity.js';
import { renderTable, type Column } from '../render.js';
import { runCommand, type Context } from '../context.js';
import type { CheckResult } from '../../http/readiness.js';

/**
 * One command that answers "is this thing all right". It is what somebody runs
 * before they ring anybody, so it prints everything it looked at and not only
 * the failures.
 */
export function doctorCommands(context: () => Context): Command {
  const command = new Command('doctor').description('check the store and the process over');

  command
    .command('run')
    .description('run every check and print what each one found')
    .option('--path <path>', 'journal to look at, defaults to the configured one')
    .action(async (options: { path?: string }) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const checks: CheckResult[] = [
          ...(await readiness({ ports: ctx.ports, config: ctx.config })).checks,
        ];

        if (ctx.config.store === 'file' || options.path !== undefined) {
          const path = options.path ?? ctx.config.storePath;
          const journal = await openJournal(path);
          try {
            const report = await estimate(journal);
            checks.push({
              name: 'journal',
              state: report.skipped > 0 ? 'warn' : 'pass',
              detail:
                report.skipped > 0
                  ? `${String(report.skipped)} lines could not be read`
                  : `${String(report.before)} entries, ${String(report.after)} live`,
              ms: 0,
            });
            checks.push({
              name: 'compaction',
              state: worthCompacting(report) ? 'warn' : 'pass',
              detail: worthCompacting(report)
                ? `${String(report.dropped)} dead entries, worth compacting`
                : 'nothing worth dropping',
              ms: 0,
            });
          } finally {
            await journal.close();
          }

          const holder = await readLock(path);
          checks.push({
            name: 'lock',
            state: 'pass',
            detail:
              holder === undefined
                ? 'nobody holds the writer lock'
                : `held by pid ${String(holder.pid)} on ${holder.host}`,
            ms: 0,
          });
        }

        const members = await ctx.ports.members.all();
        let entries = 0;
        for (const member of members) {
          const rows = await ctx.ports.checkIns.byMember(member.id);
          entries += rows.length;
          const check = verifyChain(
            rows,
            chainOf(rows).map((link) => link.digest),
          );
          if (!check.ok) {
            checks.push({ name: 'ledger', state: 'fail', detail: describeCheck(check), ms: 0 });
          }
        }
        checks.push({
          name: 'ledger',
          state: 'pass',
          detail: `${String(entries)} check ins across ${String(members.length)} members`,
          ms: 0,
        });

        const columns: Column<CheckResult>[] = [
          { header: 'check', get: (row) => row.name },
          { header: 'state', get: (row) => row.state },
          { header: 'detail', get: (row) => row.detail },
        ];
        ctx.out(renderTable(checks, columns));
        if (checks.some((check) => check.state === 'fail')) {
          ctx.fail('one or more checks failed');
        }
      });
    });

  return command;
}
