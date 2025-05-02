import { Command } from 'commander';
import { crewId as parseCrewId, crewMemberId } from '../../domain/ids.js';
import { formatCivilDate } from '../../domain/time/civil.js';
import type { CrewMember } from '../../domain/crew/member.js';
import { crewService } from '../../app/crew.service.js';
import { compareService, type CrewLine } from '../../app/compare.service.js';
import { seedDemo } from '../../app/seed.js';
import { bar, keyValues, percent, renderTable, type Column } from '../render.js';
import { runCommand, type Context } from '../context.js';

/**
 * Crew commands. A coordinator setting a rig up runs these once; a lead reads
 * the comparison every hitch.
 */
export function crewCommands(context: () => Context): Command {
  const command = new Command('crew').description('crews, membership and comparison');

  command
    .command('add')
    .description('start a crew at a site')
    .requiredOption('--name <name>', 'what the crew is called')
    .requiredOption('--site <site>', 'the installation')
    .requiredOption('--zone <zone>', 'the site timezone')
    .action(async (options: { name?: string; site?: string; zone?: string }) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const created = await crewService(ctx.ports).create({
          name: options.name ?? '',
          site: options.site ?? '',
          siteZone: options.zone ?? '',
        });
        ctx.out(`${created.id}  ${created.name} at ${created.site}`);
      });
    });

  command
    .command('enrol <crewId> <memberId>')
    .description('put somebody on a crew')
    .requiredOption('--from <date>', 'the first day they are on it')
    .option('--role <role>', 'member, lead or coordinator', 'member')
    .action(async (crew: string, member: string, options: { from?: string; role?: string }) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const entry = await crewService(ctx.ports).enrol({
          crewId: parseCrewId(crew),
          memberId: crewMemberId(member),
          from: options.from ?? '',
          role: (options.role ?? 'member') as 'member',
        });
        ctx.out(`${entry.memberId} joined ${entry.crewId} as ${entry.role}`);
      });
    });

  command
    .command('members <crewId>')
    .description('who was on a crew on a date')
    .requiredOption('--on <date>', 'the day to read it as')
    .action(async (crew: string, options: { on?: string }) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const members = await crewService(ctx.ports).membersOfCrew(
          parseCrewId(crew),
          options.on ?? '',
        );
        const columns: Column<CrewMember>[] = [
          { header: 'id', get: (row) => row.id },
          { header: 'name', get: (row) => row.displayName },
          { header: 'home', get: (row) => row.homeZone },
          { header: 'joined', get: (row) => formatCivilDate(row.joinedOn) },
        ];
        ctx.out(renderTable(members, columns));
      });
    });

  command
    .command('compare <crewId> <viewerId>')
    .description('read the crew side by side as one member would see it')
    .requiredOption('--window <window>', 'window to score')
    .requiredOption('--on <date>', 'the day to read membership as')
    .action(async (crew: string, viewer: string, options: { window?: string; on?: string }) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const report = await compareService(ctx.ports).crew(
          crewMemberId(viewer),
          parseCrewId(crew),
          options.window ?? '',
          options.on ?? '',
        );
        const columns: Column<CrewLine>[] = [
          { header: 'name', get: (row) => row.displayName },
          { header: 'owed', get: (row) => String(row.owed), align: 'right' },
          { header: 'met', get: (row) => String(row.met), align: 'right' },
          { header: 'missed', get: (row) => String(row.missed), align: 'right' },
          { header: 'rate', get: (row) => percent(row.rate), align: 'right' },
          { header: 'hidden', get: (row) => String(row.hidden), align: 'right' },
        ];
        ctx.out(renderTable(report.lines, columns));
        ctx.out('');
        ctx.out(keyValues([['crew', `${bar(report.rate)} ${percent(report.rate)}`]]));
      });
    });

  return command;
}

export function demoCommands(context: () => Context): Command {
  const command = new Command('demo').description('fill a store with something to look at');

  command
    .command('seed')
    .description('write a crew, their habits and a fortnight of check ins')
    .option('--from <date>', 'the first day of the history')
    .option('--days <days>', 'how many days to write', '28')
    .option('--adherence <share>', 'roughly how much of it gets done', '0.8')
    .action(async (options: { from?: string; days?: string; adherence?: string }) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const report = await seedDemo(ctx.ports, {
          from: options.from,
          days: Number(options.days ?? '28'),
          adherence: Number(options.adherence ?? '0.8'),
        });
        ctx.out(
          keyValues([
            ['crew', report.crewId],
            ['members', String(report.members)],
            ['habits', String(report.habits)],
            ['check ins', String(report.checkIns)],
            ['from', formatCivilDate(report.from)],
            ['to', formatCivilDate(report.to)],
          ]),
        );
      });
    });

  return command;
}
