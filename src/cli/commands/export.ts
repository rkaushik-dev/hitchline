import { writeFile } from 'node:fs/promises';
import { Command } from 'commander';
import { crewMemberId } from '../../domain/ids.js';
import { parseDateRange } from '../../domain/time/range.js';
import { adherenceService } from '../../app/adherence.service.js';
import { checkInsService } from '../../app/checkins.service.js';
import { rosterService } from '../../app/roster.service.js';
import { memberLoader } from '../../app/loader.js';
import { expandHabit } from '../../domain/schedule/expand.js';
import { checkInsToCsv, resultsToCsv, rosterToCsv } from '../../export/csv.js';
import { obligationsToCalendar } from '../../export/ics.js';
import { pluralise } from '../render.js';
import { runCommand, type Context } from '../context.js';

/**
 * Export commands. Everything goes to standard output unless a file is named,
 * so the usual pipe into a spreadsheet or a mail attachment works without the
 * tool knowing anything about either.
 */
export function exportCommands(context: () => Context): Command {
  const command = new Command('export').description('write records out for somebody else');

  async function deliver(ctx: Context, body: string, out: string | undefined): Promise<void> {
    if (out === undefined) {
      ctx.out(body.trimEnd());
      return;
    }
    await writeFile(out, body, 'utf8');
    ctx.out(`wrote ${pluralise(body.split('\n').length - 1, 'line')} to ${out}`);
  }

  command
    .command('checkins <memberId>')
    .description('every check in in a window as csv')
    .requiredOption('--window <window>', 'window to export')
    .option('--out <file>', 'write to a file rather than standard output')
    .action(async (memberId: string, options: { window?: string; out?: string }) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const rows = await checkInsService(ctx.ports).listFor(
          crewMemberId(memberId),
          parseDateRange(options.window ?? ''),
        );
        await deliver(ctx, checkInsToCsv(rows), options.out);
      });
    });

  command
    .command('roster <memberId>')
    .description('the roster over a window as csv')
    .requiredOption('--window <window>', 'window to export')
    .option('--out <file>', 'write to a file rather than standard output')
    .action(async (memberId: string, options: { window?: string; out?: string }) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const days = await rosterService(ctx.ports).daysFor(
          crewMemberId(memberId),
          options.window ?? '',
        );
        await deliver(ctx, rosterToCsv(days), options.out);
      });
    });

  command
    .command('adherence <memberId>')
    .description('every obligation and how it went as csv')
    .requiredOption('--window <window>', 'window to export')
    .option('--out <file>', 'write to a file rather than standard output')
    .action(async (memberId: string, options: { window?: string; out?: string }) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const report = await adherenceService(ctx.ports).report(
          crewMemberId(memberId),
          options.window ?? '',
        );
        await deliver(ctx, resultsToCsv(report.results), options.out);
      });
    });

  command
    .command('calendar <memberId>')
    .description('what is owed in a window as an ics calendar')
    .requiredOption('--window <window>', 'window to export')
    .option('--out <file>', 'write to a file rather than standard output')
    .action(async (memberId: string, options: { window?: string; out?: string }) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const loader = memberLoader(ctx.ports);
        const loaded = await loader.load(crewMemberId(memberId));
        const window = parseDateRange(options.window ?? '');
        const obligations = loaded.habits.flatMap((habit) =>
          expandHabit({ roster: loaded.roster, habit, window }),
        );
        const body = obligationsToCalendar([...loaded.habits], obligations, {
          name: `${loaded.roster.member.displayName} hitchline`,
          stampedAt: ctx.ports.clock.now(),
        });
        await deliver(ctx, body, options.out);
      });
    });

  return command;
}
