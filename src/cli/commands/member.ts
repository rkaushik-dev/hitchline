import { Command } from 'commander';
import { crewMember } from '../../domain/crew/member.js';
import { crewMemberId } from '../../domain/ids.js';
import { rotationAssignment } from '../../domain/rotation/assignment.js';
import { parseRotationPattern } from '../../domain/rotation/pattern.js';
import { formatCivilDate, parseCivilDate } from '../../domain/time/civil.js';
import { civilDateIn } from '../../domain/time/zone.js';
import { rosterService } from '../../app/roster.service.js';
import { renderTable, type Column } from '../render.js';
import { runCommand, type Context } from '../context.js';
import type { RosterDay } from '../../app/roster.service.js';

/**
 * Member and roster commands. These are the ones a coordinator runs while
 * setting a crew up, so they take patterns and dates the way the rota is
 * written rather than as ids and instants.
 */
export function memberCommands(context: () => Context): Command {
  const command = new Command('member').description('add and read crew members');

  command
    .command('add')
    .description('add a crew member on a rotation')
    .requiredOption('--name <name>', 'display name')
    .requiredOption('--pattern <pattern>', 'rotation pattern such as 14/14')
    .requiredOption('--anchor <date>', 'the date the cycle starts on')
    .option('--home <zone>', 'home timezone', 'UTC')
    .option('--site <zone>', 'site timezone', 'UTC')
    .option('--phase <days>', 'days into the cycle at the anchor', '0')
    .action(async (options: Record<string, string>) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const member = crewMember({
          id: crewMemberId(ctx.ports.ids.next('CrewMemberId')),
          displayName: options['name'] ?? '',
          homeZone: options['home'] ?? 'UTC',
          siteZone: options['site'] ?? 'UTC',
          joinedOn: civilDateIn(options['site'] ?? 'UTC', ctx.ports.clock.now()),
          rotation: rotationAssignment({
            pattern: parseRotationPattern(options['pattern'] ?? ''),
            anchor: parseCivilDate(options['anchor'] ?? ''),
            phase: Number(options['phase'] ?? '0'),
          }),
        });
        await ctx.ports.members.save(member);
        ctx.out(`${member.id}  ${member.displayName}`);
      });
    });

  command
    .command('list')
    .description('list the crew members this store knows')
    .action(async () => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const members = await ctx.ports.members.all();
        const columns: Column<(typeof members)[number]>[] = [
          { header: 'id', get: (row) => row.id },
          { header: 'name', get: (row) => row.displayName },
          { header: 'site', get: (row) => row.siteZone },
          { header: 'status', get: (row) => row.status },
        ];
        ctx.out(renderTable(members, columns));
      });
    });

  command
    .command('roster <memberId>')
    .description('print the roster over a window')
    .requiredOption('--window <window>', 'window such as 2025-01-06..2025-01-19')
    .action(async (memberId: string, options: Record<string, string>) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const days = await rosterService(ctx.ports).daysFor(
          crewMemberId(memberId),
          options['window'] ?? '',
        );
        const columns: Column<RosterDay>[] = [
          { header: 'date', get: (day) => formatCivilDate(day.date) },
          { header: 'role', get: (day) => day.role },
          { header: 'from', get: (day) => day.source },
          { header: 'zone', get: (day) => day.zone },
        ];
        ctx.out(renderTable(days, columns));
      });
    });

  command
    .command('hold <memberId>')
    .description('hold somebody over or send them home')
    .requiredOption('--window <window>', 'the days to change')
    .requiredOption('--role <role>', 'onshift, offshift, travel or standby')
    .requiredOption('--reason <reason>', 'why the roster changed')
    .option('--note <note>', 'a line for whoever reads it later', '')
    .action(async (memberId: string, options: Record<string, string>) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const entry = await rosterService(ctx.ports).hold({
          memberId: crewMemberId(memberId),
          range: options['window'] ?? '',
          role: (options['role'] ?? 'onshift') as RosterDay['role'],
          reason: (options['reason'] ?? 'held_over') as 'held_over',
          note: options['note'],
        });
        ctx.out(`held ${memberId} ${options['window'] ?? ''} as ${entry.role}`);
      });
    });

  return command;
}
