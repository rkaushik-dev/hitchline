import { Command } from 'commander';
import { checkInId, crewMemberId, habitId } from '../../domain/ids.js';
import { formatCadence } from '../../domain/habit/cadence.js';
import { formatTarget } from '../../domain/habit/target.js';
import { formatCivilDate } from '../../domain/time/civil.js';
import { parseDateRange } from '../../domain/time/range.js';
import type { Habit } from '../../domain/habit/habit.js';
import type { ResolvedCheckIn } from '../../domain/checkin/resolve.js';
import { habitsService } from '../../app/habits.service.js';
import { checkInsService } from '../../app/checkins.service.js';
import { renderTable, type Column } from '../render.js';
import { runCommand, type Context } from '../context.js';

/**
 * Habit and check in commands. A supervisor uses these to fix things up after
 * the fact, which is why the check in command takes a time and a source rather
 * than assuming now and the app.
 */
export function habitCommands(context: () => Context): Command {
  const command = new Command('habit').description('add, read and change habits');

  command
    .command('add <memberId>')
    .description('give somebody a habit')
    .requiredOption('--name <name>', 'what the habit is')
    .requiredOption('--cadence <cadence>', 'how often, such as 1/onshift-day')
    .option('--target <target>', 'what counts, such as "at least 20 minutes"')
    .option('--starts <date>', 'the first day it asks for anything')
    .option('--ends <date>', 'the last day it asks for anything')
    .option('--grace <days>', 'days after the fact a check in still counts', '0')
    .action(async (memberId: string, options: Record<string, string>) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const created = await habitsService(ctx.ports).create({
          memberId: crewMemberId(memberId),
          name: options['name'] ?? '',
          cadence: options['cadence'] ?? '',
          target: options['target'],
          startsOn: options['starts'],
          endsOn: options['ends'],
          graceDays: Number(options['grace'] ?? '0'),
        });
        ctx.out(`${created.id}  ${created.name}`);
      });
    });

  command
    .command('list <memberId>')
    .description('list somebody habits')
    .action(async (memberId: string) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const habits = await habitsService(ctx.ports).listFor(crewMemberId(memberId));
        const columns: Column<Habit>[] = [
          { header: 'id', get: (row) => row.id },
          { header: 'name', get: (row) => row.name },
          { header: 'cadence', get: (row) => formatCadence(row.cadence) },
          {
            header: 'target',
            get: (row) => (row.target === undefined ? '-' : formatTarget(row.target)),
          },
          { header: 'status', get: (row) => row.status },
        ];
        ctx.out(renderTable(habits, columns));
      });
    });

  command
    .command('pause <habitId>')
    .description('stop a habit asking for anything')
    .action(async (id: string) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const paused = await habitsService(ctx.ports).moveTo(habitId(id), 'paused');
        ctx.out(`${paused.id} is ${paused.status}`);
      });
    });

  command
    .command('resume <habitId>')
    .description('start it asking again')
    .action(async (id: string) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const resumed = await habitsService(ctx.ports).moveTo(habitId(id), 'active');
        ctx.out(`${resumed.id} is ${resumed.status}`);
      });
    });

  return command;
}

export function checkInCommands(context: () => Context): Command {
  const command = new Command('checkin').description('record and read check ins');

  command
    .command('record <habitId>')
    .description('record that somebody did the thing')
    .option('--at <instant>', 'when it happened, iso, defaults to now')
    .option('--reading <amount>', 'how much, in the habit unit')
    .option('--note <note>', 'a line for whoever reads it later')
    .option('--source <source>', 'app, watch, kiosk, import or supervisor', 'supervisor')
    .action(async (id: string, options: Record<string, string>) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const reading = options['reading'];
        const result = await checkInsService(ctx.ports).record({
          habitId: habitId(id),
          at: options['at'],
          reading: reading === undefined ? undefined : Number(reading),
          note: options['note'],
          source: (options['source'] ?? 'supervisor') as 'supervisor',
        });
        ctx.out(result.accepted ? `recorded ${result.entry.id}` : `already had ${result.entry.id}`);
      });
    });

  command
    .command('list <memberId>')
    .description('list what somebody has recorded')
    .option('--window <window>', 'narrow it to a window')
    .action(async (memberId: string, options: Record<string, string>) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const window = options['window'];
        const found = await checkInsService(ctx.ports).listFor(
          crewMemberId(memberId),
          window === undefined ? undefined : parseDateRange(window),
        );
        const columns: Column<ResolvedCheckIn>[] = [
          { header: 'id', get: (row) => row.checkIn.id },
          { header: 'day', get: (row) => formatCivilDate(row.day.date) },
          { header: 'role', get: (row) => row.day.role },
          {
            header: 'reading',
            get: (row) => (row.checkIn.reading === undefined ? '-' : String(row.checkIn.reading)),
            align: 'right',
          },
          { header: 'source', get: (row) => row.checkIn.source },
        ];
        ctx.out(renderTable(found, columns));
      });
    });

  command
    .command('void <checkInId>')
    .description('take a check in back out of the count')
    .action(async (id: string) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const voided = await checkInsService(ctx.ports).voidEntry(checkInId(id));
        ctx.out(`voided ${voided.id}`);
      });
    });

  return command;
}
