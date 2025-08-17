import { beforeEach, describe, expect, it } from 'vitest';
import {
  describeSync,
  settledIds,
  syncService,
  type QueuedCheckIn,
  type SyncService,
} from '../../../src/app/sync.service.js';
import { memoryStore, type MemoryStore } from '../../../src/infra/store/memory.js';
import { fixedClock } from '../../../src/infra/clock.js';
import { sequentialIds } from '../../../src/infra/id.js';
import { crewMemberId, habitId } from '../../../src/domain/ids.js';
import { aHabit, aMember } from '../../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const treadmill = habitId('hab_0123456789ABCDEF');
let store: MemoryStore;
let service: SyncService;

beforeEach(async () => {
  store = memoryStore({ clock: fixedClock('2025-01-20T09:00:00Z'), ids: sequentialIds() });
  service = syncService(store);
  await store.members.save(aMember());
  await store.habits.save(aHabit({ cadence: '1/day', startsOn: '2025-01-06' }));
});

function queued(localId: string, at: string, extra: Partial<QueuedCheckIn> = {}): QueuedCheckIn {
  return { localId, habitId: treadmill, at, ...extra };
}

describe('a handset coming back into signal', () => {
  it('stores everything it queued', async () => {
    const report = await service.push(ada, [
      queued('local-1', '2025-01-18T07:00:00Z'),
      queued('local-2', '2025-01-19T07:00:00Z'),
    ]);
    expect(report.accepted).toBe(2);
    expect(report.results.every((result) => result.outcome === 'accepted')).toBe(true);
    expect(await store.checkIns.byHabit(treadmill)).toHaveLength(2);
  });

  it('answers per item so the handset knows what to clear', async () => {
    const report = await service.push(ada, [queued('local-1', '2025-01-18T07:00:00Z')]);
    expect(report.results[0]?.localId).toBe('local-1');
    expect(report.results[0]?.checkInId).toBeDefined();
  });

  it('sorts the batch by when things happened', async () => {
    const report = await service.push(ada, [
      queued('local-2', '2025-01-19T07:00:00Z'),
      queued('local-1', '2025-01-18T07:00:00Z'),
    ]);
    expect(report.results.map((result) => result.localId)).toEqual(['local-1', 'local-2']);
  });

  it('does not double count a batch sent twice', async () => {
    const batch = [queued('local-1', '2025-01-18T07:00:00Z')];
    await service.push(ada, batch);
    const second = await service.push(ada, batch);
    expect(second.duplicates).toBe(1);
    expect(second.results[0]?.outcome).toBe('already_had_it');
    expect(await store.checkIns.byHabit(treadmill)).toHaveLength(1);
  });

  it('honours a key the handset chose itself', async () => {
    await service.push(ada, [
      queued('local-1', '2025-01-18T07:00:00Z', { idempotencyKey: 'watch-0118' }),
    ]);
    const again = await service.push(ada, [
      queued('local-9', '2025-01-18T07:00:00Z', { idempotencyKey: 'watch-0118' }),
    ]);
    expect(again.duplicates).toBe(1);
  });

  it('keeps the good rows when one is bad', async () => {
    const report = await service.push(ada, [
      queued('local-1', '2025-01-18T07:00:00Z'),
      queued('local-2', 'not a time'),
      queued('local-3', '2025-01-19T07:00:00Z'),
    ]);
    expect(report.accepted).toBe(2);
    expect(report.refused).toBe(1);
    expect(report.results.find((result) => result.localId === 'local-2')?.message).toContain(
      'iso timestamp',
    );
  });

  it('refuses a row for a habit that has gone', async () => {
    const report = await service.push(ada, [
      { localId: 'local-1', habitId: 'hab_9999999999999999', at: '2025-01-18T07:00:00Z' },
    ]);
    expect(report.refused).toBe(1);
    expect(report.results[0]?.outcome).toBe('refused');
  });

  it('refuses a row dated in the future rather than storing it', async () => {
    const report = await service.push(ada, [queued('local-1', '2025-02-01T07:00:00Z')]);
    expect(report.refused).toBe(1);
  });

  it('caps an absurd batch rather than working through it', async () => {
    const batch = Array.from({ length: 600 }, (_, index) =>
      queued(`local-${String(index)}`, '2025-01-18T07:00:00Z', {
        idempotencyKey: `queued-${String(index)}`,
      }),
    );
    const report = await service.push(ada, batch);
    expect(report.results).toHaveLength(500);
  });

  it('reads an empty batch as nothing to do', async () => {
    const report = await service.push(ada, []);
    expect(report).toMatchObject({ accepted: 0, duplicates: 0, refused: 0 });
  });
});

describe('what the handset may clear', () => {
  it('is everything that will not change its answer', async () => {
    const report = await service.push(ada, [
      queued('local-1', '2025-01-18T07:00:00Z'),
      queued('local-2', 'not a time'),
    ]);
    expect(settledIds(report)).toEqual(['local-1']);
  });

  it('reads back in one line', async () => {
    const report = await service.push(ada, [queued('local-1', '2025-01-18T07:00:00Z')]);
    expect(describeSync(report)).toBe('1 stored, 0 already here, 0 refused');
  });
});
