import { writeFile } from 'node:fs/promises';
import { Command } from 'commander';
import { API_VERSION } from '../../http/app.js';
import { apiMarkdown, tableOfContents } from '../../http/docs.js';
import { catalogueMarkdown } from '../../http/catalogue.js';
import { openApiDocument } from '../../http/manifest.js';
import { runCommand, type Context } from '../context.js';

/**
 * Writing the reference out. It is a command rather than a build step because
 * the same output is what somebody wants on a terminal when they are half way
 * through a curl and cannot remember the parameter name.
 */
export function docsCommands(context: () => Context): Command {
  const command = new Command('docs').description('write the api reference out');

  command
    .command('api')
    .description('the whole reference as markdown')
    .option('--out <file>', 'write to a file rather than standard output')
    .option('--base <url>', 'base url to show in the examples')
    .action(async (options: { out?: string; base?: string }) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const body = apiMarkdown({ version: API_VERSION, baseUrl: options.base });
        if (options.out === undefined) {
          ctx.out(body.trimEnd());
          return;
        }
        await writeFile(options.out, body, 'utf8');
        ctx.out(`wrote the reference to ${options.out}`);
      });
    });

  command
    .command('routes')
    .description('just the list of routes')
    .action(async () => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, () => {
        ctx.out(tableOfContents());
        return Promise.resolve();
      });
    });

  command
    .command('errors')
    .description('the error codes and what to do about each')
    .action(async () => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, () => {
        ctx.out(catalogueMarkdown().trimEnd());
        return Promise.resolve();
      });
    });

  command
    .command('openapi')
    .description('the openapi document')
    .option('--out <file>', 'write to a file rather than standard output')
    .action(async (options: { out?: string }) => {
      const ctx = context();
      process.exitCode = await runCommand(ctx, async () => {
        const body = `${JSON.stringify(openApiDocument({ version: API_VERSION }), null, 2)}\n`;
        if (options.out === undefined) {
          ctx.out(body.trimEnd());
          return;
        }
        await writeFile(options.out, body, 'utf8');
        ctx.out(`wrote the openapi document to ${options.out}`);
      });
    });

  return command;
}
