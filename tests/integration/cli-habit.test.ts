import { beforeEach, describe, expect, it } from 'vitest';
import { buildProgram } from '../../src/cli/main.js';
import {
  EXIT_FAILED,
  EXIT_MISUSED,
  EXIT_OK,
  recordingContext,
  type Recorder,
} from '../../src/cli/context.js';
import { readConfig } from '../../src/config/env.js';
import { memoryStore, type MemoryStore } from '../../src/infra/store/memory.js';
import { fixedClock } from '../../src/infra/clock.js';
import { sequentialIds } from '../../src/infra/id.js';
import { crewMemberId } from '../../src/domain/ids.js';
import { aMember } from '../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const config = { ...readConfig({}), store: 'memory' as const };
let store: MemoryStore;
let context: Recorder;

beforeEach(async () => {
  store = memoryStore({ clock: fixedClock('2025-01-10T09:00:00Z'), ids: sequentialIds() });
  context = recordingContext(store, config);
  await store.members.save(aMember());
});

async function run(...argv: string[]): Promise<number> {
  process.exitCode = EXIT_OK;
  const program = buildProgram(() => context);
  program.exitOverride();
  await program.parseAsync(['node', 'hitchline', ...argv]);
  return process.exitCode ?? EXIT_OK;
}

async function addHabit(...extra: string[]): Promise<string> {
  context.clear();
  await run(
    'habit',
    'add',
    ada,
    '--name',
    'Twenty minutes on the treadmill',
    '--cadence',
    '1/day',
    '--starts',
    '2025-01-06',
    ...extra,
  );
  const id = context.text().split(' ')[0] ?? '';
  context.clear();
  return id;
}

describe('habit add', () => {
  it('stores one and prints the id', async () => {
    const id = await addHabit();
    expect(id.startsWith('hab_')).toBe(true);
    expect(await store.habits.byMember(ada)).toHaveLength(1);
  });

  it('takes a target and a grace window', async () => {
    const id = await addHabit('--target', 'at least 20 minutes', '--grace', '2');
    const stored = await store.habits.byId(id as never);
    expect(stored?.target?.amount).toBe(20);
    expect(stored?.graceDays).toBe(2);
  });

  it('refuses a cadence it cannot read', async () => {
    const code = await run('habit', 'add', ada, '--name', 'Row', '--cadence', 'every day');
    expect(code).toBe(EXIT_MISUSED);
    expect(context.errorText()).toContain('3/hitch');
  });

  it('refuses a member it does not hold', async () => {
    const code = await run(
      'habit',
      'add',
      'mem_9999999999999999',
      '--name',
      'Row',
      '--cadence',
      '1/day',
    );
    expect(code).toBe(EXIT_FAILED);
  });
});

describe('habit list', () => {
  it('prints a row for each habit', async () => {
    await addHabit();
    await run('habit', 'list', ada);
    const lines = context.text().split('\n');
    expect(lines).toHaveLength(3);
    expect(lines[2]).toContain('1/day');
    expect(lines[2]).toContain('-');
  });

  it('prints the target when there is one', async () => {
    await addHabit('--target', 'at least 20 minutes');
    await run('habit', 'list', ada);
    expect(context.text()).toContain('at least 20 minutes');
  });
});

describe('pausing and resuming', () => {
  it('moves a habit between states', async () => {
    const id = await addHabit();
    expect(await run('habit', 'pause', id)).toBe(EXIT_OK);
    expect((await store.habits.byId(id as never))?.status).toBe('paused');
    await run('habit', 'resume', id);
    expect((await store.habits.byId(id as never))?.status).toBe('active');
  });

  it('reports a habit it does not hold', async () => {
    expect(await run('habit', 'pause', 'hab_9999999999999999')).toBe(EXIT_FAILED);
  });
});

describe('recording a check in', () => {
  it('records one and says so', async () => {
    const id = await addHabit();
    const code = await run('checkin', 'record', id, '--at', '2025-01-08T07:00:00Z');
    expect(code).toBe(EXIT_OK);
    expect(context.text()).toContain('recorded chk_');
  });

  it('takes a reading', async () => {
    const id = await addHabit('--target', 'at least 20 minutes');
    await run('checkin', 'record', id, '--reading', '25');
    const stored = await store.checkIns.byHabit(id as never);
    expect(stored[0]?.reading).toBe(25);
  });

  it('defaults the source to supervisor so a backfill is allowed', async () => {
    const id = await addHabit();
    const code = await run('checkin', 'record', id, '--at', '2025-01-06T07:00:00Z');
    expect(code).toBe(EXIT_OK);
    const stored = await store.checkIns.byHabit(id as never);
    expect(stored[0]?.source).toBe('supervisor');
  });

  it('refuses a time in the future', async () => {
    const id = await addHabit();
    const code = await run('checkin', 'record', id, '--at', '2025-02-01T07:00:00Z');
    expect(code).toBe(EXIT_MISUSED);
  });
});

describe('listing and voiding check ins', () => {
  it('lists what is recorded with its rotation day', async () => {
    const id = await addHabit();
    await run('checkin', 'record', id, '--at', '2025-01-08T07:00:00Z');
    context.clear();
    await run('checkin', 'list', ada);
    const lines = context.text().split('\n');
    expect(lines[2]).toContain('2025-01-08');
    expect(lines[2]).toContain('onshift');
  });

  it('narrows to a window', async () => {
    const id = await addHabit();
    await run('checkin', 'record', id, '--at', '2025-01-08T07:00:00Z');
    context.clear();
    await run('checkin', 'list', ada, '--window', '2025-01-01..2025-01-05');
    expect(context.text().split('\n')).toHaveLength(2);
  });

  it('voids one and drops it from the list', async () => {
    const id = await addHabit();
    await run('checkin', 'record', id, '--at', '2025-01-08T07:00:00Z');
    const recorded = (await store.checkIns.byHabit(id as never))[0];
    context.clear();
    expect(await run('checkin', 'void', recorded?.id ?? '')).toBe(EXIT_OK);
    context.clear();
    await run('checkin', 'list', ada);
    expect(context.text().split('\n')).toHaveLength(2);
  });

  it('reports a check in it does not hold', async () => {
    expect(await run('checkin', 'void', 'chk_9999999999999999')).toBe(EXIT_FAILED);
  });
});
