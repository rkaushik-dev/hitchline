import { beforeEach, describe, expect, it } from 'vitest';
import {
  describeImport,
  importService,
  type ImportService,
} from '../../../src/app/import.service.js';
import { memoryStore, type MemoryStore } from '../../../src/infra/store/memory.js';
import { fixedClock } from '../../../src/infra/clock.js';
import { sequentialIds } from '../../../src/infra/id.js';
import { habitId } from '../../../src/domain/ids.js';
import { aHabit, aMember } from '../../support/fixtures.js';

const treadmill = habitId('hab_0123456789ABCDEF');
let store: MemoryStore;
let service: ImportService;

beforeEach(async () => {
  store = memoryStore({ clock: fixedClock('2025-01-20T09:00:00Z'), ids: sequentialIds() });
  service = importService(store);
  await store.members.save(aMember());
  await store.habits.save(aHabit({ cadence: '1/day', startsOn: '2025-01-06' }));
});

const header = 'habit_id,at,reading,note,idempotency_key';

describe('a clean file', () => {
  it('stores every row', async () => {
    const text = [
      header,
      `${treadmill},2025-01-10T07:00:00Z,20,,`,
      `${treadmill},2025-01-11T07:00:00Z,25,,`,
    ].join('\n');
    const report = await service.checkIns(text);
    expect(report).toEqual({ read: 2, accepted: 2, duplicates: 0, failures: [] });
    expect(await store.checkIns.byHabit(treadmill)).toHaveLength(2);
  });

  it('keeps the readings and the notes', async () => {
    const text = [header, `${treadmill},2025-01-10T07:00:00Z,20,"felt slow, cold",`].join('\n');
    await service.checkIns(text);
    const stored = (await store.checkIns.byHabit(treadmill))[0];
    expect(stored?.reading).toBe(20);
    expect(stored?.note).toBe('felt slow, cold');
  });

  it('marks the rows as an import', async () => {
    const text = [header, `${treadmill},2025-01-10T07:00:00Z,,,`].join('\n');
    await service.checkIns(text);
    expect((await store.checkIns.byHabit(treadmill))[0]?.source).toBe('import');
  });

  it('takes a source', async () => {
    const text = [header, `${treadmill},2025-01-10T07:00:00Z,,,`].join('\n');
    await service.checkIns(text, { source: 'supervisor' });
    expect((await store.checkIns.byHabit(treadmill))[0]?.source).toBe('supervisor');
  });
});

describe('rows that will not go in', () => {
  it('reports a bad row and keeps the good ones', async () => {
    const text = [
      header,
      `${treadmill},2025-01-10T07:00:00Z,20,,`,
      `${treadmill},not a time,20,,`,
      `${treadmill},2025-01-12T07:00:00Z,20,,`,
    ].join('\n');
    const report = await service.checkIns(text);
    expect(report.accepted).toBe(2);
    expect(report.failures).toHaveLength(1);
    expect(report.failures[0]?.line).toBe(3);
  });

  it('reports a habit it has never heard of', async () => {
    const text = [header, `hab_9999999999999999,2025-01-10T07:00:00Z,,,`].join('\n');
    const report = await service.checkIns(text);
    expect(report.failures[0]?.message).toContain('does not exist');
  });

  it('reports a reading that is not a number', async () => {
    const text = [header, `${treadmill},2025-01-10T07:00:00Z,quite a lot,,`].join('\n');
    const report = await service.checkIns(text);
    expect(report.failures[0]?.message).toContain('is not a number');
  });

  it('reports an id that is not an id at all', async () => {
    const text = [header, `nonsense,2025-01-10T07:00:00Z,,,`].join('\n');
    expect((await service.checkIns(text)).failures).toHaveLength(1);
  });
});

describe('repeats', () => {
  it('counts a repeated idempotency key rather than storing it twice', async () => {
    const text = [
      header,
      `${treadmill},2025-01-10T07:00:00Z,20,,paper-0110`,
      `${treadmill},2025-01-10T07:00:00Z,20,,paper-0110`,
    ].join('\n');
    const report = await service.checkIns(text);
    expect(report.accepted).toBe(1);
    expect(report.duplicates).toBe(1);
    expect(await store.checkIns.byHabit(treadmill)).toHaveLength(1);
  });

  it('is safe to run the same file twice', async () => {
    const text = [header, `${treadmill},2025-01-10T07:00:00Z,20,,paper-0110`].join('\n');
    await service.checkIns(text);
    const second = await service.checkIns(text);
    expect(second.duplicates).toBe(1);
    expect(await store.checkIns.byHabit(treadmill)).toHaveLength(1);
  });
});

describe('a dry run', () => {
  it('checks the rows without storing anything', async () => {
    const text = [header, `${treadmill},2025-01-10T07:00:00Z,20,,`].join('\n');
    const report = await service.checkIns(text, { dryRun: true });
    expect(report.accepted).toBe(1);
    expect(await store.checkIns.byHabit(treadmill)).toEqual([]);
  });

  it('still reports a habit it cannot find', async () => {
    const text = [header, `hab_9999999999999999,2025-01-10T07:00:00Z,,,`].join('\n');
    const report = await service.checkIns(text, { dryRun: true });
    expect(report.failures).toHaveLength(1);
  });
});

describe('the file itself', () => {
  it('refuses one missing a required column', async () => {
    await expect(service.checkIns('habit_id\nhab_1\n')).rejects.toThrow(/missing the columns at/);
  });

  it('refuses one with no header', async () => {
    await expect(service.checkIns('')).rejects.toThrow(/has no header line/);
  });

  it('reads a file with only a header as nothing to do', async () => {
    expect(await service.checkIns(`${header}\n`)).toEqual({
      read: 0,
      accepted: 0,
      duplicates: 0,
      failures: [],
    });
  });
});

describe('describing a run', () => {
  it('reads back in one line', () => {
    expect(describeImport({ read: 3, accepted: 2, duplicates: 1, failures: [] })).toBe(
      '3 read, 2 accepted, 1 already there, 0 refused',
    );
  });
});
