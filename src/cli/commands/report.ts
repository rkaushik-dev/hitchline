import { Command } from 'commander';
import { crewMemberId, habitId } from '../../domain/ids.js';
import { formatCivilDate } from '../../domain/time/civil.js';
import { formatDateRange } from '../../domain/time/range.js';
import type { ObligationResult } from '../../domain/adherence/match.js';
import type { PeriodSummary } from '../../domain/adherence/summary.js';
import { describeTrend, type RoleSplit } from '../../domain/adherence/analytics.js';
import { adherenceService } from '../../app/adherence.service.js';
import { nudgesService } from '../../app/nudges.service.js';
import {
  bar,
  heading,
  keyValues,
  percent,
  pluralise,
  renderTable,
  type Column,
} from '../render.js';
import { runCommand, type Context } from '../context.js';

/**
 * Reports. These are what somebody actually runs on a Monday morning, so they
 * print the numbers a crew argues about: how much was owed, how much landed,
 * and which hitch it happened in.
 */
export function reportCommands(context: () => Context): Command {
  const command = new Command('report').description('read adherence back out');

  command
    .command('adherence <memberId>')
    .description('how a window went')
    .requiredOption('--window <window>', 'window such as 2025-01-06..2025-01-19')
    .option('--by-hitch', 'break it down by turn of the rotation', false)
    .option('--shape', 'show the role split, the trend and the busiest hour', false)
    .action(
      async (
        memberId: string,
        options: { window?: string; byHitch?: boolean; shape?: boolean },
      ) => {
        const ctx = context();
        process.exitCode = await runCommand(ctx, async () => {
          const report = await adherenceService(ctx.ports).report(
            crewMemberId(memberId),
            options.window ?? '',
          );
          ctx.out(heading(`adherence for ${memberId}`));
          ctx.out(
            keyValues([
              ['window', formatDateRange(report.window)],
              ['as of', formatCivilDate(report.asOf)],
              ['owed', String(report.summary.owed)],
              ['met', String(report.summary.met)],
              ['partial', String(report.summary.partial)],
              ['missed', String(report.summary.missed)],
              ['pending', String(report.summary.pending)],
              ['shielded', pluralise(report.summary.shieldedDays, 'day')],
              ['rate', `${bar(report.summary.rate)} ${percent(report.summary.rate)}`],
            ]),
          );
          if (options.byHitch === true) {
            ctx.out('');
            ctx.out(renderTable(report.byHitch, summaryColumns()));
          }
          if (options.shape === true) {
            ctx.out('');
            ctx.out(renderTable(report.byRole, roleColumns()));
            ctx.out('');
            ctx.out(
              keyValues([
                ['trend', describeTrend(report.trend)],
                [
                  'busiest hour',
                  report.busiestHour === undefined
                    ? 'nothing recorded'
                    : `${String(report.busiestHour).padStart(2, '0')}:00`,
                ],
              ]),
            );
          }
        });
      },
    );

  command
    .command('open <memberId>')
    .description('what is still open in a window')
    .requiredOption('--window <window>', 'window to look at')
    .action(async (memberId: string, options: Record<string, string>) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const open = await adherenceService(ctx.ports).open(
          crewMemberId(memberId),
          options['window'] ?? '',
        );
        ctx.out(renderTable(open, resultColumns()));
      });
    });

  command
    .command('streak <memberId> <habitId>')
    .description('the run on one habit')
    .requiredOption('--window <window>', 'window to walk')
    .action(async (memberId: string, id: string, options: Record<string, string>) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const summary = await adherenceService(ctx.ports).streak(
          crewMemberId(memberId),
          habitId(id),
          options['window'] ?? '',
        );
        ctx.out(
          keyValues([
            ['current', String(summary.current)],
            ['longest', String(summary.longest)],
            ['shielded', String(summary.shielded)],
            [
              'last met',
              summary.lastMetOn === undefined ? '-' : formatCivilDate(summary.lastMetOn),
            ],
            ['broken on', summary.brokenOn === undefined ? '-' : formatCivilDate(summary.brokenOn)],
          ]),
        );
      });
    });

  command
    .command('nudges <memberId>')
    .description('what the planner would send')
    .requiredOption('--window <window>', 'window to plan over')
    .action(async (memberId: string, options: Record<string, string>) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const planned = await nudgesService(ctx.ports).plan(
          crewMemberId(memberId),
          options['window'] ?? '',
        );
        ctx.out(
          renderTable(planned, [
            { header: 'day', get: (row) => formatCivilDate(row.onDate) },
            { header: 'at', get: (row) => new Date(row.at.epochMs).toISOString() },
            { header: 'why', get: (row) => row.reason },
            { header: 'habit', get: (row) => row.habitId },
          ]),
        );
      });
    });

  return command;
}

function roleColumns(): Column<RoleSplit>[] {
  return [
    { header: 'role', get: (row) => row.role },
    { header: 'owed', get: (row) => String(row.owed), align: 'right' },
    { header: 'met', get: (row) => String(row.met), align: 'right' },
    { header: 'rate', get: (row) => percent(row.rate), align: 'right' },
  ];
}

function summaryColumns(): Column<PeriodSummary>[] {
  return [
    { header: 'window', get: (row) => formatDateRange(row.range) },
    { header: 'owed', get: (row) => String(row.owed), align: 'right' },
    { header: 'met', get: (row) => String(row.met), align: 'right' },
    { header: 'missed', get: (row) => String(row.missed), align: 'right' },
    { header: 'rate', get: (row) => percent(row.rate), align: 'right' },
  ];
}

function resultColumns(): Column<ObligationResult>[] {
  return [
    { header: 'due', get: (row) => formatCivilDate(row.obligation.dueOn) },
    { header: 'scope', get: (row) => row.obligation.scope },
    { header: 'habit', get: (row) => row.obligation.habitId },
    {
      header: 'done',
      get: (row) => `${String(row.count)}/${String(row.obligation.times)}`,
      align: 'right',
    },
    { header: 'status', get: (row) => row.status },
  ];
}
