import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  compact,
  estimate,
  renumber,
  survivors,
  worthCompacting,
} from '../../../../src/infra/store/compact.js';
import {
  openJournal,
  type Journal,
  type JournalEntry,
} from '../../../../src/infra/store/journal.js';

const at = '2025-01-10T09:00:00.000Z';
let directory: string;
let journal: Journal;

beforeEach(async () => {
  directory = await mkdtemp(join(tmpdir(), 'hitchline-compact-'));
  journal = await openJournal(join(directory, 'store.jsonl'));
});

afterEach(async () => {
  await journal.close();
  await rm(directory, { recursive: true, force: true });
});

function entry(
  seq: number,
  kind: JournalEntry['kind'],
  op: JournalEntry['op'],
  row: Record<string, unknown>,
): JournalEntry {
  return { seq, at, kind, op, row };
}

describe('survivors', () => {
  it('keeps only the last write for a keyed record', () => {
    const kept = survivors([
      entry(1, 'member', 'put', { id: 'mem_1', v: 1 }),
      entry(2, 'member', 'put', { id: 'mem_1', v: 2 }),
    ]);
    expect(kept).toHaveLength(1);
    expect(kept[0]?.row).toEqual({ id: 'mem_1', v: 2 });
  });

  it('keeps one entry per key', () => {
    const kept = survivors([
      entry(1, 'member', 'put', { id: 'mem_1' }),
      entry(2, 'member', 'put', { id: 'mem_2' }),
      entry(3, 'member', 'put', { id: 'mem_1' }),
    ]);
    expect(kept.map((item) => item.seq)).toEqual([2, 3]);
  });

  it('drops a record that was deleted', () => {
    const kept = survivors([
      entry(1, 'member', 'put', { id: 'mem_1' }),
      entry(2, 'member', 'delete', { id: 'mem_1' }),
    ]);
    expect(kept).toEqual([]);
  });

  it('keeps a record written again after a delete', () => {
    const kept = survivors([
      entry(1, 'member', 'put', { id: 'mem_1' }),
      entry(2, 'member', 'delete', { id: 'mem_1' }),
      entry(3, 'member', 'put', { id: 'mem_1', v: 3 }),
    ]);
    expect(kept).toHaveLength(1);
    expect(kept[0]?.seq).toBe(3);
  });

  it('keeps every entry of an append only kind', () => {
    const kept = survivors([
      entry(1, 'override', 'put', { memberId: 'mem_1', range: 'a' }),
      entry(2, 'override', 'put', { memberId: 'mem_1', range: 'b' }),
      entry(3, 'shield', 'put', { memberId: 'mem_1', range: 'c' }),
    ]);
    expect(kept).toHaveLength(3);
  });

  it('keys a membership on the crew and the member together', () => {
    const kept = survivors([
      entry(1, 'membership', 'put', { crewId: 'crw_1', memberId: 'mem_1' }),
      entry(2, 'membership', 'put', { crewId: 'crw_2', memberId: 'mem_1' }),
      entry(3, 'membership', 'put', { crewId: 'crw_1', memberId: 'mem_1', to: '2025-06-30' }),
    ]);
    expect(kept.map((item) => item.seq)).toEqual([2, 3]);
  });
});

describe('renumber', () => {
  it('runs from one with no gaps', () => {
    const renumbered = renumber([
      entry(4, 'member', 'put', { id: 'mem_1' }),
      entry(9, 'member', 'put', { id: 'mem_2' }),
    ]);
    expect(renumbered.map((item) => item.seq)).toEqual([1, 2]);
  });

  it('leaves the rest of the entry alone', () => {
    const [only] = renumber([entry(4, 'habit', 'put', { id: 'hab_1' })]);
    expect(only?.kind).toBe('habit');
    expect(only?.row).toEqual({ id: 'hab_1' });
  });
});

describe('compacting a real journal', () => {
  it('rewrites the file and reports what it dropped', async () => {
    await journal.append('member', 'put', { id: 'mem_1', v: 1 }, at);
    await journal.append('member', 'put', { id: 'mem_1', v: 2 }, at);
    await journal.append('habit', 'put', { id: 'hab_1' }, at);

    const report = await compact(journal);
    expect(report.before).toBe(3);
    expect(report.after).toBe(2);
    expect(report.dropped).toBe(1);

    const after = await journal.read();
    expect(after.entries.map((item) => item.seq)).toEqual([1, 2]);
  });

  it('carries on appending after a compaction', async () => {
    await journal.append('member', 'put', { id: 'mem_1', v: 1 }, at);
    await journal.append('member', 'put', { id: 'mem_1', v: 2 }, at);
    await compact(journal);
    const next = await journal.append('member', 'put', { id: 'mem_2' }, at);
    expect(next.seq).toBe(2);
    expect((await journal.read()).entries).toHaveLength(2);
  });

  it('does nothing to an already tidy journal', async () => {
    await journal.append('member', 'put', { id: 'mem_1' }, at);
    const report = await compact(journal);
    expect(report.dropped).toBe(0);
  });

  it('estimates without writing', async () => {
    await journal.append('member', 'put', { id: 'mem_1', v: 1 }, at);
    await journal.append('member', 'put', { id: 'mem_1', v: 2 }, at);
    const report = await estimate(journal);
    expect(report.dropped).toBe(1);
    expect((await journal.read()).entries).toHaveLength(2);
  });
});

describe('worthCompacting', () => {
  it('is true once enough is dead weight', () => {
    expect(worthCompacting({ before: 10, after: 6, dropped: 4, skipped: 0 })).toBe(true);
  });

  it('is false when little would be saved', () => {
    expect(worthCompacting({ before: 10, after: 9, dropped: 1, skipped: 0 })).toBe(false);
  });

  it('is false for an empty journal', () => {
    expect(worthCompacting({ before: 0, after: 0, dropped: 0, skipped: 0 })).toBe(false);
  });

  it('takes a ratio', () => {
    expect(worthCompacting({ before: 10, after: 9, dropped: 1, skipped: 0 }, 0.1)).toBe(true);
  });
});
