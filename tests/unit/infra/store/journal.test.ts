import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  collect,
  fold,
  isRecordKind,
  openJournal,
  readJournal,
  type Journal,
} from '../../../../src/infra/store/journal.js';

let directory: string;
let path: string;
let journal: Journal;

beforeEach(async () => {
  directory = await mkdtemp(join(tmpdir(), 'hitchline-journal-'));
  path = join(directory, 'nested', 'store.jsonl');
  journal = await openJournal(path);
});

afterEach(async () => {
  await journal.close();
  await rm(directory, { recursive: true, force: true });
});

const at = '2025-01-10T09:00:00.000Z';

describe('record kinds', () => {
  it('knows the ones it stores', () => {
    expect(isRecordKind('checkin')).toBe(true);
    expect(isRecordKind('invoice')).toBe(false);
  });
});

describe('appending', () => {
  it('numbers entries from one', async () => {
    const first = await journal.append('member', 'put', { id: 'mem_1' }, at);
    const second = await journal.append('member', 'put', { id: 'mem_2' }, at);
    expect(first.seq).toBe(1);
    expect(second.seq).toBe(2);
  });

  it('writes one json object per line', async () => {
    await journal.append('member', 'put', { id: 'mem_1' }, at);
    await journal.append('habit', 'put', { id: 'hab_1' }, at);
    const text = await readFile(path, 'utf8');
    expect(text.trim().split('\n')).toHaveLength(2);
  });

  it('creates the directory it was pointed at', async () => {
    const report = await readJournal(path);
    expect(report.entries).toEqual([]);
  });

  it('reads back what it wrote', async () => {
    await journal.append('member', 'put', { id: 'mem_1', displayName: 'Ada' }, at);
    const report = await journal.read();
    expect(report.entries).toHaveLength(1);
    expect(report.entries[0]?.row).toEqual({ id: 'mem_1', displayName: 'Ada' });
    expect(report.entries[0]?.kind).toBe('member');
  });
});

describe('reopening', () => {
  it('carries on numbering where it left off', async () => {
    await journal.append('member', 'put', { id: 'mem_1' }, at);
    await journal.close();
    const reopened = await openJournal(path);
    const next = await reopened.append('member', 'put', { id: 'mem_2' }, at);
    await reopened.close();
    expect(next.seq).toBe(2);
  });

  it('reads an absent file as empty', async () => {
    const report = await readJournal(join(directory, 'missing.jsonl'));
    expect(report).toEqual({ entries: [], skipped: 0, lastSeq: 0 });
  });
});

describe('damaged lines', () => {
  it('counts what it could not read rather than throwing', async () => {
    await journal.append('member', 'put', { id: 'mem_1' }, at);
    await journal.close();
    const good = await readFile(path, 'utf8');
    await writeFile(path, `${good}not json at all\n{"seq":2}\n`, 'utf8');
    const report = await readJournal(path);
    expect(report.entries).toHaveLength(1);
    expect(report.skipped).toBe(2);
    journal = await openJournal(path);
  });

  it('skips a line with an unknown kind', async () => {
    await writeFile(
      path,
      `${JSON.stringify({ seq: 1, at, kind: 'invoice', op: 'put', row: {} })}\n`,
      'utf8',
    );
    const report = await readJournal(path);
    expect(report.entries).toEqual([]);
    expect(report.skipped).toBe(1);
  });

  it('ignores blank lines without counting them', async () => {
    await writeFile(path, '\n\n', 'utf8');
    const report = await readJournal(path);
    expect(report.skipped).toBe(0);
  });
});

describe('rewriting', () => {
  it('replaces the file and resets the sequence', async () => {
    await journal.append('member', 'put', { id: 'mem_1' }, at);
    await journal.append('member', 'put', { id: 'mem_2' }, at);
    const report = await journal.read();
    await journal.rewrite(report.entries.slice(1));
    const after = await journal.read();
    expect(after.entries).toHaveLength(1);
    expect(after.entries[0]?.row).toEqual({ id: 'mem_2' });
  });

  it('can empty the file', async () => {
    await journal.append('member', 'put', { id: 'mem_1' }, at);
    await journal.rewrite([]);
    expect((await journal.read()).entries).toEqual([]);
    const next = await journal.append('member', 'put', { id: 'mem_9' }, at);
    expect(next.seq).toBe(1);
  });
});

describe('folding', () => {
  const entries = [
    { seq: 1, at, kind: 'member' as const, op: 'put' as const, row: { id: 'mem_1', v: 1 } },
    { seq: 2, at, kind: 'member' as const, op: 'put' as const, row: { id: 'mem_1', v: 2 } },
    { seq: 3, at, kind: 'member' as const, op: 'put' as const, row: { id: 'mem_2', v: 1 } },
    { seq: 4, at, kind: 'habit' as const, op: 'put' as const, row: { id: 'hab_1' } },
    { seq: 5, at, kind: 'member' as const, op: 'delete' as const, row: { id: 'mem_2' } },
  ];
  const keyOf = (row: Record<string, unknown>): string => String(row['id']);

  it('keeps the last write for each key', () => {
    const state = fold(entries, 'member', keyOf);
    expect(state.get('mem_1')).toEqual({ id: 'mem_1', v: 2 });
  });

  it('honours a delete', () => {
    expect(fold(entries, 'member', keyOf).has('mem_2')).toBe(false);
  });

  it('ignores other kinds', () => {
    expect(fold(entries, 'member', keyOf).size).toBe(1);
    expect(fold(entries, 'habit', keyOf).size).toBe(1);
  });

  it('collects every live row of a kind', () => {
    expect(collect(entries, 'member')).toHaveLength(3);
    expect(collect(entries, 'habit')).toHaveLength(1);
  });
});
