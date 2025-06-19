import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildProgram } from '../../src/cli/main.js';
import { EXIT_OK, recordingContext, type Recorder } from '../../src/cli/context.js';
import { readConfig } from '../../src/config/env.js';
import { fileStore, type FileStore } from '../../src/infra/store/file.js';
import { memoryStore } from '../../src/infra/store/memory.js';
import { fixedClock } from '../../src/infra/clock.js';
import { sequentialIds } from '../../src/infra/id.js';
import { aMember } from '../support/fixtures.js';

let directory: string;
let path: string;
let store: FileStore;
let context: Recorder;

beforeEach(async () => {
  directory = await mkdtemp(join(tmpdir(), 'hitchline-doctor-'));
  path = join(directory, 'hitchline.jsonl');
  store = await fileStore({
    path,
    clock: fixedClock('2025-06-23T09:00:00Z'),
    ids: sequentialIds(),
  });
  context = recordingContext(store, { ...readConfig({}), store: 'file', storePath: path });
});

afterEach(async () => {
  await store.close();
  await rm(directory, { recursive: true, force: true });
});

async function run(...argv: string[]): Promise<number> {
  process.exitCode = EXIT_OK;
  const program = buildProgram(() => context);
  program.exitOverride();
  await program.parseAsync(['node', 'hitchline', ...argv]);
  return process.exitCode ?? EXIT_OK;
}

describe('the doctor', () => {
  it('checks the store, the clock and the journal', async () => {
    expect(await run('doctor', 'run')).toBe(EXIT_OK);
    const text = context.text();
    expect(text).toContain('store');
    expect(text).toContain('clock');
    expect(text).toContain('journal');
    expect(text).toContain('lock');
  });

  it('says who holds the writer lock', async () => {
    await run('doctor', 'run');
    expect(context.text()).toContain('held by pid');
  });

  it('counts the check ins it verified', async () => {
    await store.members.save(aMember());
    context.clear();
    await run('doctor', 'run');
    expect(context.text()).toContain('0 check ins across 1 members');
  });

  it('says when the journal is worth compacting', async () => {
    await store.members.save(aMember());
    await store.members.save(aMember({ displayName: 'Ada Nkemdirim' }));
    context.clear();
    await run('doctor', 'run');
    expect(context.text()).toContain('worth compacting');
  });

  it('leaves the journal checks out for a memory store', async () => {
    const memory = memoryStore({ clock: fixedClock('2025-06-23T09:00:00Z') });
    context = recordingContext(memory, { ...readConfig({}), store: 'memory' });
    await run('doctor', 'run');
    expect(context.text()).not.toContain('journal');
    expect(context.text()).not.toContain('writer lock');
    expect(context.text()).toContain('memory only');
  });

  it('reads a journal it was pointed at', async () => {
    const memory = memoryStore({ clock: fixedClock('2025-06-23T09:00:00Z') });
    context = recordingContext(memory, { ...readConfig({}), store: 'memory' });
    await run('doctor', 'run', '--path', path);
    expect(context.text()).toContain('journal');
  });
});
