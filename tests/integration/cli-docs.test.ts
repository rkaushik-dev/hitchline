import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildProgram } from '../../src/cli/main.js';
import { EXIT_OK, recordingContext, type Recorder } from '../../src/cli/context.js';
import { readConfig } from '../../src/config/env.js';
import { memoryStore } from '../../src/infra/store/memory.js';
import { fixedClock } from '../../src/infra/clock.js';
import { ROUTES } from '../../src/http/manifest.js';
import { API_VERSION } from '../../src/http/app.js';

const config = { ...readConfig({}), store: 'memory' as const };
let context: Recorder;
let directory: string;

beforeEach(async () => {
  directory = await mkdtemp(join(tmpdir(), 'hitchline-docs-'));
  context = recordingContext(memoryStore({ clock: fixedClock('2025-07-21T09:00:00Z') }), config);
});

afterEach(async () => {
  await rm(directory, { recursive: true, force: true });
});

async function run(...argv: string[]): Promise<number> {
  process.exitCode = EXIT_OK;
  const program = buildProgram(() => context);
  program.exitOverride();
  await program.parseAsync(['node', 'hitchline', ...argv]);
  return process.exitCode ?? EXIT_OK;
}

describe('the api reference', () => {
  it('opens with the version and lists every route', async () => {
    expect(await run('docs', 'api')).toBe(EXIT_OK);
    const text = context.text();
    expect(text.startsWith(`# hitchline api ${API_VERSION}`)).toBe(true);
    for (const route of ROUTES) {
      expect(text).toContain(`### ${route.method} ${route.path}`);
    }
  });

  it('says what scope each route needs', async () => {
    await run('docs', 'api');
    expect(context.text()).toContain('needs the admin scope');
    expect(context.text()).toContain('no token needed');
  });

  it('describes the parameters a route takes', async () => {
    await run('docs', 'api');
    expect(context.text()).toContain('| window | query | yes |');
  });

  it('ends with the error codes', async () => {
    await run('docs', 'api');
    expect(context.text()).toContain('## Errors');
    expect(context.text()).toContain('validation_failed');
  });

  it('takes a base url', async () => {
    await run('docs', 'api', '--base', 'https://hitchline.example');
    expect(context.text()).toContain('https://hitchline.example');
  });

  it('writes it to a file when asked', async () => {
    const file = join(directory, 'api.md');
    await run('docs', 'api', '--out', file);
    expect(context.text()).toContain('wrote the reference');
    expect(await readFile(file, 'utf8')).toContain('# hitchline api');
  });
});

describe('the shorter forms', () => {
  it('lists the routes on their own', async () => {
    await run('docs', 'routes');
    expect(context.text().split('\n')).toHaveLength(ROUTES.length);
  });

  it('prints the error table on its own', async () => {
    await run('docs', 'errors');
    expect(context.text().split('\n')[0]).toContain('| code |');
  });

  it('writes an openapi document', async () => {
    const file = join(directory, 'openapi.json');
    await run('docs', 'openapi', '--out', file);
    const parsed: unknown = JSON.parse(await readFile(file, 'utf8'));
    expect((parsed as { openapi: string }).openapi).toBe('3.1.0');
  });

  it('prints the openapi document when no file is named', async () => {
    await run('docs', 'openapi');
    expect(context.text().startsWith('{')).toBe(true);
  });
});
