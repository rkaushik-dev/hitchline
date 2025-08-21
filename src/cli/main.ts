import { Command } from 'commander';
import { API_VERSION } from '../http/app.js';
import { readConfig } from '../config/env.js';
import { closeStore, openStore } from '../infra/store/open.js';
import { consoleContext, type Context } from './context.js';
import { memberCommands } from './commands/member.js';
import { checkInCommands, habitCommands } from './commands/habit.js';
import { reportCommands } from './commands/report.js';
import { storeCommands } from './commands/store.js';
import { crewCommands, demoCommands } from './commands/crew.js';
import { exportCommands } from './commands/export.js';
import { doctorCommands } from './commands/doctor.js';
import { docsCommands } from './commands/docs.js';
import { configCommands } from './commands/config.js';

/**
 * The operator command line. It opens the same store the server does, which is
 * the point: a coordinator can fix a roster from a terminal while the api is
 * running against the same journal.
 */
export function buildProgram(context: () => Context): Command {
  const program = new Command('hitchline');
  program
    .description('adherence tracking for crews working rotational hitches')
    .version(API_VERSION)
    .showHelpAfterError()
    .addHelpText(
      'after',
      [
        '',
        'Examples:',
        '  hitchline member add --name "Ada Vasquez" --pattern 14/14 --anchor 2025-01-06',
        '    --home Europe/Lisbon --site Europe/Oslo',
        '  hitchline habit add mem_... --name "Twenty minutes" --cadence 1/onshift-day',
        '  hitchline report adherence mem_... --window 2025-01-06..2025-01-19 --shape',
        '  hitchline store status',
        '',
        'A window is written 2025-01-06..2025-01-19 and both ends are included.',
      ].join(String.fromCharCode(10)),
    );
  program.addCommand(memberCommands(context));
  program.addCommand(habitCommands(context));
  program.addCommand(checkInCommands(context));
  program.addCommand(reportCommands(context));
  program.addCommand(storeCommands(context));
  program.addCommand(crewCommands(context));
  program.addCommand(demoCommands(context));
  program.addCommand(exportCommands(context));
  program.addCommand(doctorCommands(context));
  program.addCommand(docsCommands(context));
  program.addCommand(configCommands(context));
  return program;
}

export async function main(argv: readonly string[]): Promise<void> {
  const config = readConfig();
  const store = await openStore(config);
  try {
    const program = buildProgram(() => consoleContext(store, config));
    await program.parseAsync([...argv]);
  } finally {
    await closeStore(store);
  }
}
