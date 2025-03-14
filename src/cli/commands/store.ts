import { Command } from 'commander';
import { ValidationError } from '../../domain/errors.js';
import { formatInstant } from '../../domain/time/zone.js';
import { openJournal } from '../../infra/store/journal.js';
import { compact, estimate, worthCompacting } from '../../infra/store/compact.js';
import {
  describeBackup,
  readBackup,
  restore,
  takeBackup,
  writeBackup,
} from '../../infra/store/backup.js';
import { keyValues, pluralise } from '../render.js';
import { runCommand, type Context } from '../context.js';

/**
 * Store maintenance. These commands open the journal directly rather than
 * going through the repositories, because they are about the file rather than
 * about what is in it.
 */
export function storeCommands(context: () => Context): Command {
  const command = new Command('store').description('look after the journal on disk');

  function pathOf(ctx: Context, given: string | undefined): string {
    const path = given ?? ctx.config.storePath;
    if (ctx.config.store !== 'file' && given === undefined) {
      throw ValidationError.field('path', 'is needed when the store is not a file');
    }
    return path;
  }

  command
    .command('status')
    .description('say what the journal holds and whether it is worth compacting')
    .option('--path <path>', 'journal to look at')
    .action(async (options: { path?: string }) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const journal = await openJournal(pathOf(ctx, options.path));
        try {
          const report = await estimate(journal);
          ctx.out(
            keyValues([
              ['path', journal.path],
              ['entries', String(report.before)],
              ['live', String(report.after)],
              ['dead', String(report.dropped)],
              ['unreadable', String(report.skipped)],
              ['compact', worthCompacting(report) ? 'worth doing' : 'not yet'],
            ]),
          );
        } finally {
          await journal.close();
        }
      });
    });

  command
    .command('compact')
    .description('drop the lines the store would not load')
    .option('--path <path>', 'journal to compact')
    .action(async (options: { path?: string }) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const journal = await openJournal(pathOf(ctx, options.path));
        try {
          const report = await compact(journal);
          ctx.out(`compacted ${String(report.before)} entries down to ${String(report.after)}`);
        } finally {
          await journal.close();
        }
      });
    });

  command
    .command('backup <file>')
    .description('write a backup of the journal')
    .option('--path <path>', 'journal to back up')
    .action(async (file: string, options: { path?: string }) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const journal = await openJournal(pathOf(ctx, options.path));
        try {
          const backup = await takeBackup(journal, formatInstant(ctx.ports.clock.now()));
          await writeBackup(file, backup);
          ctx.out(describeBackup(backup));
        } finally {
          await journal.close();
        }
      });
    });

  command
    .command('restore <file>')
    .description('write a backup back into a journal')
    .option('--path <path>', 'journal to write into')
    .option('--force', 'write over a journal that already holds something', false)
    .action(async (file: string, options: { path?: string; force?: boolean }) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const backup = await readBackup(file);
        const journal = await openJournal(pathOf(ctx, options.path));
        try {
          const report = await restore(journal, backup, { force: options.force === true });
          ctx.out(`restored ${pluralise(report.restored, 'entry', 'entries')} from ${file}`);
        } finally {
          await journal.close();
        }
      });
    });

  return command;
}
