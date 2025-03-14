import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildProgram } from '../../src/cli/main.js';
import { EXIT_FAILED, EXIT_OK, recordingContext, type Recorder } from '../../src/cli/context.js';
import { readConfig } from '../../src/config/env.js';
import { fileStore, type FileStore } from '../../src/infra/store/file.js';
import { fixedClock } from '../../src/infra/clock.js';
import { sequentialIds } from '../../src/infra/id.js';
import { aMember } from '../support/fixtures.js';

let directory: string;
let path: string;
let store: FileStore;
let context: Recorder;

beforeEach(async () => {
  directory = await mkdtemp(join(tmpdir(), 'hitchline-cli-store-'));
  path = join(directory, 'hitchline.jsonl');
  store = await fileStore({
    path,
    clock: fixedClock('2025-03-14T09:00:00Z'),
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

async function seed(): Promise<void> {
  await store.members.save(aMember());
  await store.members.save(aMember({ displayName: 'Ada Nkemdirim' }));
  await store.flush();
  context.clear();
}

describe('store status', () => {
  it('counts the live and the dead entries', async () => {
    await seed();
    expect(await run('store', 'status')).toBe(EXIT_OK);
    const text = context.text();
    expect(text).toContain('entries     2');
    expect(text).toContain('live        1');
    expect(text).toContain('dead        1');
  });

  it('says whether compacting is worth it', async () => {
    await seed();
    await run('store', 'status');
    expect(context.text()).toContain('worth doing');
  });

  it('reads an empty journal', async () => {
    await run('store', 'status');
    expect(context.text()).toContain('entries     0');
  });
});

describe('store compact', () => {
  it('rewrites the file and says what it dropped', async () => {
    await seed();
    expect(await run('store', 'compact')).toBe(EXIT_OK);
    expect(context.text()).toContain('compacted 2 entries down to 1');
    expect((await readFile(path, 'utf8')).trim().split('\n')).toHaveLength(1);
  });

  it('leaves a tidy journal alone', async () => {
    await store.members.save(aMember());
    await store.flush();
    context.clear();
    await run('store', 'compact');
    expect(context.text()).toContain('down to 1');
  });
});

describe('store backup and restore', () => {
  it('writes a backup and describes it', async () => {
    await seed();
    const file = join(directory, 'backup.json');
    expect(await run('store', 'backup', file)).toBe(EXIT_OK);
    expect(context.text()).toContain('member 1');
    expect(JSON.parse(await readFile(file, 'utf8'))).toMatchObject({ version: 1 });
  });

  it('restores into an empty journal', async () => {
    await seed();
    const file = join(directory, 'backup.json');
    await run('store', 'backup', file);
    const other = join(directory, 'other.jsonl');
    context.clear();
    expect(await run('store', 'restore', file, '--path', other)).toBe(EXIT_OK);
    expect(context.text()).toContain('restored 1 entry');
  });

  it('refuses to write over a journal that holds something', async () => {
    await seed();
    const file = join(directory, 'backup.json');
    await run('store', 'backup', file);
    context.clear();
    expect(await run('store', 'restore', file)).toBe(EXIT_FAILED);
    expect(context.errorText()).toContain('conflict');
  });

  it('writes over it when forced', async () => {
    await seed();
    const file = join(directory, 'backup.json');
    await run('store', 'backup', file);
    context.clear();
    expect(await run('store', 'restore', file, '--force')).toBe(EXIT_OK);
  });

  it('reports a backup file it cannot read', async () => {
    const code = await run('store', 'restore', join(directory, 'nothing.json'));
    await expect(Promise.resolve(code)).resolves.toBeGreaterThan(EXIT_OK);
  });
});
