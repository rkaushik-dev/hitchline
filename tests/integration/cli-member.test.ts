import { beforeEach, describe, expect, it } from 'vitest';
import { buildProgram } from '../../src/cli/main.js';
import {
  EXIT_FAILED,
  EXIT_MISUSED,
  EXIT_OK,
  recordingContext,
  runCommand,
  type Recorder,
} from '../../src/cli/context.js';
import { readConfig } from '../../src/config/env.js';
import { memoryStore, type MemoryStore } from '../../src/infra/store/memory.js';
import { fixedClock } from '../../src/infra/clock.js';
import { sequentialIds } from '../../src/infra/id.js';
import { ConflictError, NotFoundError, ValidationError } from '../../src/domain/errors.js';

const config = { ...readConfig({}), store: 'memory' as const };
let store: MemoryStore;
let context: Recorder;

beforeEach(() => {
  store = memoryStore({ clock: fixedClock('2025-03-10T09:00:00Z'), ids: sequentialIds() });
  context = recordingContext(store, config);
});

async function run(...argv: string[]): Promise<number> {
  process.exitCode = EXIT_OK;
  const program = buildProgram(() => context);
  program.exitOverride();
  await program.parseAsync(['node', 'hitchline', ...argv]);
  return process.exitCode ?? EXIT_OK;
}

describe('running a command body', () => {
  it('answers zero when it works', async () => {
    expect(await runCommand(context, () => Promise.resolve())).toBe(EXIT_OK);
  });

  it('answers two on a validation failure and prints the detail', async () => {
    const code = await runCommand(context, () =>
      Promise.reject(ValidationError.field('cadence', 'is not recognised')),
    );
    expect(code).toBe(EXIT_MISUSED);
    expect(context.errorText()).toContain('cadence is not recognised');
  });

  it('answers one on any other domain failure', async () => {
    const code = await runCommand(context, () => Promise.reject(new ConflictError('clash')));
    expect(code).toBe(EXIT_FAILED);
    expect(context.errorText()).toContain('conflict: clash');
  });

  it('lets an unexpected error through with its stack', async () => {
    await expect(runCommand(context, () => Promise.reject(new Error('bug')))).rejects.toThrow(
      'bug',
    );
  });

  it('prints a detail with no path against a dash', async () => {
    await runCommand(context, () =>
      Promise.reject(
        new ValidationError('habit is invalid', [{ path: '', message: 'nothing fits' }]),
      ),
    );
    expect(context.errorText()).toContain('- nothing fits');
  });
});

describe('member add', () => {
  it('stores a member and prints the id', async () => {
    const code = await run(
      'member',
      'add',
      '--name',
      'Ada Vasquez',
      '--pattern',
      '14/14',
      '--anchor',
      '2025-01-06',
      '--home',
      'Europe/Lisbon',
      '--site',
      'Europe/Oslo',
    );
    expect(code).toBe(EXIT_OK);
    expect(await store.members.all()).toHaveLength(1);
    expect(context.text()).toContain('Ada Vasquez');
  });

  it('refuses a pattern it cannot read', async () => {
    const code = await run(
      'member',
      'add',
      '--name',
      'Ada Vasquez',
      '--pattern',
      'two weeks on',
      '--anchor',
      '2025-01-06',
    );
    expect(code).toBe(EXIT_MISUSED);
    expect(context.errorText()).toContain('cannot read the segment');
  });

  it('refuses a zone the platform does not know', async () => {
    const code = await run(
      'member',
      'add',
      '--name',
      'Ada Vasquez',
      '--pattern',
      '14/14',
      '--anchor',
      '2025-01-06',
      '--site',
      'North Sea/Alpha',
    );
    expect(code).toBe(EXIT_MISUSED);
  });
});

describe('member list', () => {
  it('prints a table with a header', async () => {
    await run(
      'member',
      'add',
      '--name',
      'Ada Vasquez',
      '--pattern',
      '14/14',
      '--anchor',
      '2025-01-06',
    );
    context.clear();
    await run('member', 'list');
    const lines = context.text().split('\n');
    expect(lines[0]).toContain('id');
    expect(lines[2]).toContain('Ada Vasquez');
  });

  it('prints just a header when the store is empty', async () => {
    await run('member', 'list');
    expect(context.text().split('\n')).toHaveLength(2);
  });
});

describe('member roster', () => {
  async function addAda(): Promise<string> {
    await run(
      'member',
      'add',
      '--name',
      'Ada Vasquez',
      '--pattern',
      '14/14',
      '--anchor',
      '2025-01-06',
      '--home',
      'Europe/Lisbon',
      '--site',
      'Europe/Oslo',
    );
    const id = (await store.members.all())[0]?.id ?? '';
    context.clear();
    return id;
  }

  it('prints a row for each day', async () => {
    const id = await addAda();
    await run('member', 'roster', id, '--window', '2025-01-19..2025-01-21');
    const lines = context.text().split('\n');
    expect(lines).toHaveLength(5);
    expect(lines[2]).toContain('onshift');
    expect(lines[3]).toContain('offshift');
  });

  it('reports a member it does not hold', async () => {
    const code = await run(
      'member',
      'roster',
      'mem_9999999999999999',
      '--window',
      '2025-01-19..2025-01-21',
    );
    expect(code).toBe(EXIT_FAILED);
    expect(context.errorText()).toContain('not_found');
  });
});

describe('member hold', () => {
  it('records an override', async () => {
    await run(
      'member',
      'add',
      '--name',
      'Ada Vasquez',
      '--pattern',
      '14/14',
      '--anchor',
      '2025-01-06',
    );
    const id = (await store.members.all())[0]?.id ?? '';
    const code = await run(
      'member',
      'hold',
      id,
      '--window',
      '2025-01-20..2025-01-22',
      '--role',
      'onshift',
      '--reason',
      'held_over',
    );
    expect(code).toBe(EXIT_OK);
    expect(await store.overrides.byMember(id as never)).toHaveLength(1);
  });

  it('reports a clash', async () => {
    await run(
      'member',
      'add',
      '--name',
      'Ada Vasquez',
      '--pattern',
      '14/14',
      '--anchor',
      '2025-01-06',
    );
    const id = (await store.members.all())[0]?.id ?? '';
    const args = ['member', 'hold', id, '--role', 'onshift', '--reason', 'held_over'];
    await run(...args, '--window', '2025-01-20..2025-01-22');
    const code = await run(...args, '--window', '2025-01-21..2025-01-25');
    expect(code).toBe(EXIT_FAILED);
  });
});

describe('the not found path', () => {
  it('reports a missing member from a hold', async () => {
    const code = await run(
      'member',
      'hold',
      'mem_9999999999999999',
      '--window',
      '2025-01-20..2025-01-22',
      '--role',
      'onshift',
      '--reason',
      'held_over',
    );
    expect(code).toBe(EXIT_FAILED);
    expect(context.errorText()).toContain('crew member');
  });

  it('leaves the store alone', async () => {
    await run(
      'member',
      'hold',
      'mem_9999999999999999',
      '--window',
      '2025-01-20..2025-01-22',
      '--role',
      'onshift',
      '--reason',
      'held_over',
    );
    expect(await store.members.all()).toEqual([]);
  });
});

describe('NotFoundError shape', () => {
  it('names the resource the cli prints', () => {
    expect(new NotFoundError('crew member', 'mem_1').message).toContain('crew member');
  });
});
