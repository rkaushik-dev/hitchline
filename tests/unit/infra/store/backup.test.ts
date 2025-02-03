import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  BACKUP_VERSION,
  backupCounts,
  describeBackup,
  readBackup,
  restore,
  takeBackup,
  writeBackup,
} from '../../../../src/infra/store/backup.js';
import { openJournal, type Journal } from '../../../../src/infra/store/journal.js';
import { ConflictError, ValidationError } from '../../../../src/domain/errors.js';

const at = '2025-01-10T09:00:00.000Z';
let directory: string;
let journal: Journal;
let path: string;

beforeEach(async () => {
  directory = await mkdtemp(join(tmpdir(), 'hitchline-backup-'));
  path = join(directory, 'store.jsonl');
  journal = await openJournal(path);
});

afterEach(async () => {
  await journal.close();
  await rm(directory, { recursive: true, force: true });
});

async function seed(): Promise<void> {
  await journal.append('member', 'put', { id: 'mem_1', v: 1 }, at);
  await journal.append('member', 'put', { id: 'mem_1', v: 2 }, at);
  await journal.append('habit', 'put', { id: 'hab_1' }, at);
  await journal.append('override', 'put', { memberId: 'mem_1', range: 'a' }, at);
}

describe('taking a backup', () => {
  it('holds the compacted entries', async () => {
    await seed();
    const backup = await takeBackup(journal, at);
    expect(backup.entries).toHaveLength(3);
    expect(backup.version).toBe(BACKUP_VERSION);
    expect(backup.takenAt).toBe(at);
  });

  it('renumbers from one', async () => {
    await seed();
    const backup = await takeBackup(journal, at);
    expect(backup.entries.map((entry) => entry.seq)).toEqual([1, 2, 3]);
  });

  it('backs up an empty journal', async () => {
    const backup = await takeBackup(journal, at);
    expect(backup.entries).toEqual([]);
  });
});

describe('writing and reading a backup file', () => {
  it('round trips', async () => {
    await seed();
    const file = join(directory, 'nested', 'backup.json');
    await writeBackup(file, await takeBackup(journal, at));
    const back = await readBackup(file);
    expect(back.entries).toHaveLength(3);
    expect(back.takenAt).toBe(at);
  });

  it('writes readable json', async () => {
    await seed();
    const file = join(directory, 'backup.json');
    await writeBackup(file, await takeBackup(journal, at));
    expect((await readFile(file, 'utf8')).startsWith('{\n  "version"')).toBe(true);
  });

  it('refuses a file that is not json', async () => {
    const file = join(directory, 'broken.json');
    await writeFile(file, 'not json', 'utf8');
    await expect(readBackup(file)).rejects.toThrow(/is not json/);
  });

  it('refuses a version it does not read', async () => {
    const file = join(directory, 'old.json');
    await writeFile(file, JSON.stringify({ version: 99, takenAt: at, entries: [] }), 'utf8');
    await expect(readBackup(file)).rejects.toThrow(/this build reads 1/);
  });

  it('refuses an entry with an unknown kind', async () => {
    const file = join(directory, 'odd.json');
    const entries = [{ seq: 1, at, kind: 'invoice', op: 'put', row: {} }];
    await writeFile(file, JSON.stringify({ version: 1, takenAt: at, entries }), 'utf8');
    await expect(readBackup(file)).rejects.toThrow(ValidationError);
  });

  it('refuses an entry with no row', async () => {
    const file = join(directory, 'odd.json');
    const entries = [{ seq: 1, at, kind: 'member', op: 'put' }];
    await writeFile(file, JSON.stringify({ version: 1, takenAt: at, entries }), 'utf8');
    await expect(readBackup(file)).rejects.toThrow(/has no row at position 0/);
  });
});

describe('restoring', () => {
  it('writes a backup into an empty journal', async () => {
    await seed();
    const backup = await takeBackup(journal, at);
    const target = await openJournal(join(directory, 'restored.jsonl'));
    const report = await restore(target, backup);
    expect(report.restored).toBe(3);
    expect((await target.read()).entries).toHaveLength(3);
    await target.close();
  });

  it('refuses to write over a journal that holds something', async () => {
    await seed();
    const backup = await takeBackup(journal, at);
    await expect(restore(journal, backup)).rejects.toThrow(ConflictError);
  });

  it('writes over it when forced', async () => {
    await seed();
    const backup = await takeBackup(journal, at);
    const report = await restore(journal, backup, { force: true });
    expect(report.restored).toBe(3);
    expect((await journal.read()).entries).toHaveLength(3);
  });
});

describe('reading a backup', () => {
  it('counts the entries by kind', async () => {
    await seed();
    const backup = await takeBackup(journal, at);
    expect(backupCounts(backup)).toEqual({ member: 1, habit: 1, override: 1 });
  });

  it('describes itself in one line', async () => {
    await seed();
    expect(describeBackup(await takeBackup(journal, at))).toBe(
      `3 entries taken ${at}: habit 1, member 1, override 1`,
    );
  });
});
