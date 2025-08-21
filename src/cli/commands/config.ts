import { Command } from 'commander';
import { API_VERSION } from '../../http/app.js';
import { describeConfig } from '../../config/env.js';
import { keyValues } from '../render.js';
import { runCommand, type Context } from '../context.js';

/**
 * What this process thinks it is running as. Half the support questions about
 * a tool like this are answered by printing the settings it actually read,
 * rather than the ones somebody meant to set.
 */
export function configCommands(context: () => Context): Command {
  const command = new Command('config').description('read back the settings in force');

  command
    .command('show')
    .description('print every setting, with the tokens redacted')
    .action(async () => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, () => {
        const { config } = ctx;
        ctx.out(
          keyValues([
            ['api version', API_VERSION],
            ['store', config.store],
            ['store path', config.store === 'file' ? config.storePath : 'not used'],
            ['host', config.host],
            ['port', String(config.port)],
            ['log level', config.logLevel],
            ['default zone', config.defaultZone],
            ['writes a minute', String(config.writesPerMinute)],
            ['burst', String(config.burst)],
            ['tokens', `${String(config.tokens.length)} loaded`],
            ['summary', describeConfig(config)],
          ]),
        );
        return Promise.resolve();
      });
    });

  command
    .command('tokens')
    .description('list the tokens by member and scope, never the token itself')
    .action(async () => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, () => {
        if (ctx.config.tokens.length === 0) {
          ctx.out('no tokens are loaded, so every request will be turned away');
          return Promise.resolve();
        }
        for (const token of ctx.config.tokens) {
          ctx.out(`${token.memberId}  ${token.scopes.join('|')}  ${token.token.slice(0, 4)}...`);
        }
        return Promise.resolve();
      });
    });

  return command;
}
