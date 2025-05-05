import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildProgram } from '../../src/cli/main.js';
import { EXIT_FAILED, EXIT_OK, recordingContext, type Recorder } from '../../src/cli/context.js';
import { readConfig } from '../../src/config/env.js';
import { memoryStore, type MemoryStore } from '../../src/infra/store/memory.js';
import { fixedClock } from '../../src/infra/clock.js';
import { sequentialIds } from '../../src/infra/id.js';

const config = { ...readConfig({}), store: 'memory' as const };
let store: MemoryStore;
let context: Recorder;
let directory: string;
let member: string;

beforeEach(async () => {
  directory = await mkdtemp(join(tmpdir(), 'hitchline-export-'));
  store = memoryStore({ clock: fixedClock('2025-02-10T09:00:00Z'), ids: sequentialIds() });
  context = recordingContext(store, config);
  await run('demo', 'seed', '--days', '14', '--adherence', '1');
  member = (await store.members.all())[0]?.id ?? '';
  context.clear();
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

const window = '2025-01-06..2025-01-19';

describe('exporting check ins', () => {
  it('writes a csv header and a line for each row', async () => {
    expect(await run('export', 'checkins', member, '--window', window)).toBe(EXIT_OK);
    const lines = context.text().split('\r\n');
    expect(lines[0]).toBe('id,habit_id,member_id,at,rotation_day,role,zone,reading,source,note');
    expect(lines.length).toBeGreaterThan(2);
  });

  it('writes to a file when asked', async () => {
    const file = join(directory, 'checkins.csv');
    await run('export', 'checkins', member, '--window', window, '--out', file);
    expect(context.text()).toContain('lines to');
    expect(await readFile(file, 'utf8')).toContain('rotation_day');
  });

  it('reports a member it does not hold', async () => {
    const code = await run('export', 'checkins', 'mem_9999999999999999', '--window', window);
    expect(code).toBe(EXIT_FAILED);
  });
});

describe('exporting a roster', () => {
  it('writes a line for each day', async () => {
    await run('export', 'roster', member, '--window', '2025-01-06..2025-01-08');
    const lines = context.text().split('\r\n');
    expect(lines[0]).toBe('date,role,source,zone');
    expect(lines).toHaveLength(4);
  });
});

describe('exporting adherence', () => {
  it('writes a line for each obligation', async () => {
    await run('export', 'adherence', member, '--window', window);
    const lines = context.text().split('\r\n');
    expect(lines[0]).toContain('habit_id,scope,window,due_on');
    expect(lines.length).toBeGreaterThan(2);
  });
});

describe('exporting a calendar', () => {
  it('writes a calendar with events in it', async () => {
    await run('export', 'calendar', member, '--window', window);
    const body = context.text();
    expect(body.startsWith('BEGIN:VCALENDAR')).toBe(true);
    expect(body).toContain('BEGIN:VEVENT');
    expect(body).toContain('X-WR-CALNAME:Ada Vasquez hitchline');
  });

  it('writes it to a file when asked', async () => {
    const file = join(directory, 'hitch.ics');
    await run('export', 'calendar', member, '--window', window, '--out', file);
    expect(await readFile(file, 'utf8')).toContain('END:VCALENDAR');
  });
});
