import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdtemp, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  backupDirectoryFor,
  backupSizes,
  describeStatistics,
  pruneBackups,
  statisticsOf,
} from '../../../../src/infra/store/statistics.js';
import type { JournalEntry } from '../../../../src/infra/store/journal.js';
import { ValidationError } from '../../../../src/domain/errors.js';

let directory: string;

beforeEach(async () => {
  directory = await mkdtemp(join(tmpdir(), 'hitchline-stats-'));
});

afterEach(async () => {
  await rm(directory, { recursive: true, force: true });
});

function entry(kind: JournalEntry['kind'], at: string): JournalEntry {
  return { seq: 1, at, kind, op: 'put', row: {} };
}

describe('statistics', () => {
  const entries = [
    entry('member', '2025-07-01T09:00:00.000Z'),
    entry('habit', '2025-07-01T10:00:00.000Z'),
    entry('checkin', '2025-07-02T09:00:00.000Z'),
    entry('checkin', '2025-07-03T09:00:00.000Z'),
  ];

  it('counts the entries by kind', () => {
    expect(statisticsOf(entries).byKind).toEqual({ member: 1, habit: 1, checkin: 2 });
  });

  it('names the first and the last', () => {
    const stats = statisticsOf(entries);
    expect(stats.firstAt).toBe('2025-07-01T09:00:00.000Z');
    expect(stats.lastAt).toBe('2025-07-03T09:00:00.000Z');
  });

  it('counts the days something was written', () => {
    expect(statisticsOf(entries).writers).toBe(3);
  });

  it('reads an empty journal without inventing anything', () => {
    const stats = statisticsOf([]);
    expect(stats).toEqual({
      entries: 0,
      byKind: {},
      firstAt: undefined,
      lastAt: undefined,
      writers: 0,
    });
  });

  it('reads back in one line', () => {
    expect(describeStatistics(statisticsOf(entries))).toBe(
      '4 entries over 3 days: checkin 2, habit 1, member 1',
    );
  });
});

describe('pruning backups', () => {
  async function backups(...names: string[]): Promise<void> {
    for (const name of names) {
      await writeFile(join(directory, name), '{}', 'utf8');
    }
  }

  it('keeps the newest few by name', async () => {
    await backups('2025-07-01.json', '2025-07-02.json', '2025-07-03.json');
    const report = await pruneBackups(directory, { keep: 2 });
    expect(report.kept).toEqual(['2025-07-03.json', '2025-07-02.json']);
    expect(report.removed).toEqual(['2025-07-01.json']);
    expect(await readdir(directory)).toHaveLength(2);
  });

  it('leaves everything alone when there are few enough', async () => {
    await backups('2025-07-01.json');
    expect((await pruneBackups(directory, { keep: 5 })).removed).toEqual([]);
  });

  it('ignores anything that is not a backup', async () => {
    await backups('2025-07-01.json', '2025-07-02.json');
    await writeFile(join(directory, 'notes.txt'), 'hello', 'utf8');
    await pruneBackups(directory, { keep: 1 });
    expect(await readdir(directory)).toContain('notes.txt');
  });

  it('says what it would do without doing it', async () => {
    await backups('2025-07-01.json', '2025-07-02.json');
    const report = await pruneBackups(directory, { keep: 1, dryRun: true });
    expect(report.removed).toEqual(['2025-07-01.json']);
    expect(await readdir(directory)).toHaveLength(2);
  });

  it('refuses to keep nothing', async () => {
    await expect(pruneBackups(directory, { keep: 0 })).rejects.toThrow(ValidationError);
  });
});

describe('sizes and paths', () => {
  it('measures each file it is given', async () => {
    const path = join(directory, 'one.json');
    await writeFile(path, '{"a":1}', 'utf8');
    expect(await backupSizes([path])).toEqual({ 'one.json': 7 });
  });

  it('puts backups beside the journal', () => {
    expect(backupDirectoryFor('/data/hitchline.jsonl')).toBe(join('/data', 'backups'));
  });
});
