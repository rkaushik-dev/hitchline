import { beforeEach, describe, expect, it } from 'vitest';
import {
  BACKFILL_LIMIT_DAYS,
  checkInsService,
  type CheckInsService,
} from '../../../src/app/checkins.service.js';
import { memoryStore, type MemoryStore } from '../../../src/infra/store/memory.js';
import { fixedClock, type MutableClock } from '../../../src/infra/clock.js';
import { sequentialIds } from '../../../src/infra/id.js';
import { checkInId, habitId } from '../../../src/domain/ids.js';
import { formatCivilDate } from '../../../src/domain/time/civil.js';
import { parseDateRange } from '../../../src/domain/time/range.js';
import { ConflictError, NotFoundError, ValidationError } from '../../../src/domain/errors.js';
import { aHabit, aMember } from '../../support/fixtures.js';

const treadmill = habitId('hab_0123456789ABCDEF');
let store: MemoryStore;
let service: CheckInsService;
let clock: MutableClock;

beforeEach(async () => {
  clock = fixedClock('2025-01-10T09:00:00Z');
  store = memoryStore({ clock, ids: sequentialIds() });
  service = checkInsService(store);
  await store.members.save(aMember());
  await store.habits.save(aHabit({ cadence: '1/day' }));
});

describe('recording a check in', () => {
  it('stamps it with the clock when no time is given', async () => {
    const { entry, accepted } = await service.record({ habitId: treadmill });
    expect(accepted).toBe(true);
    expect(entry.at.epochMs).toBe(clock.now().epochMs);
    expect(entry.recordedAt.epochMs).toBe(clock.now().epochMs);
  });

  it('mints an id and stores it', async () => {
    const { entry } = await service.record({ habitId: treadmill });
    expect(entry.id.startsWith('chk_')).toBe(true);
    expect(await store.checkIns.byId(entry.id)).toEqual(entry);
  });

  it('keeps the reading and the note', async () => {
    const { entry } = await service.record({
      habitId: treadmill,
      reading: 25,
      note: 'before the shift',
    });
    expect(entry.reading).toBe(25);
    expect(entry.note).toBe('before the shift');
  });

  it('takes a time in the past', async () => {
    const { entry } = await service.record({ habitId: treadmill, at: '2025-01-08T06:00:00Z' });
    expect(entry.at.epochMs).toBeLessThan(entry.recordedAt.epochMs);
  });

  it('refuses a time in the future', async () => {
    await expect(
      service.record({ habitId: treadmill, at: '2025-01-11T09:00:00Z' }),
    ).rejects.toThrow(/in the future/);
  });

  it('allows a few minutes of handset drift', async () => {
    await expect(
      service.record({ habitId: treadmill, at: '2025-01-10T09:02:00Z' }),
    ).resolves.toBeDefined();
  });

  it('refuses a backfill past the limit', async () => {
    await expect(
      service.record({ habitId: treadmill, at: '2024-12-01T09:00:00Z' }),
    ).rejects.toThrow(new RegExp(`${BACKFILL_LIMIT_DAYS} day limit`));
  });

  it('lets a supervisor or an import go further back', async () => {
    await expect(
      service.record({ habitId: treadmill, at: '2025-01-02T09:00:00Z', source: 'supervisor' }),
    ).resolves.toBeDefined();
  });

  it('refuses a day the habit was not running', async () => {
    await store.habits.save(aHabit({ cadence: '1/day', startsOn: '2025-01-09' }));
    await expect(
      service.record({ habitId: treadmill, at: '2025-01-08T09:00:00Z' }),
    ).rejects.toThrow(/not running/);
  });

  it('refuses a habit it has never heard of', async () => {
    await expect(service.record({ habitId: habitId('hab_9999999999999999') })).rejects.toThrow(
      NotFoundError,
    );
  });
});

describe('idempotency', () => {
  it('gives back the first entry when a key repeats', async () => {
    const first = await service.record({ habitId: treadmill, idempotencyKey: 'watch-0110' });
    const second = await service.record({ habitId: treadmill, idempotencyKey: 'watch-0110' });
    expect(second.accepted).toBe(false);
    expect(second.entry.id).toBe(first.entry.id);
    expect(await store.checkIns.byHabit(treadmill)).toHaveLength(1);
  });

  it('accepts the same key on another habit', async () => {
    await store.habits.save(aHabit({ id: 'hab_FEDCBA9876543210', cadence: '1/day' }));
    await service.record({ habitId: treadmill, idempotencyKey: 'watch-0110' });
    const other = await service.record({
      habitId: habitId('hab_FEDCBA9876543210'),
      idempotencyKey: 'watch-0110',
    });
    expect(other.accepted).toBe(true);
  });
});

describe('voiding and amending', () => {
  it('voids an entry and stamps when', async () => {
    const { entry } = await service.record({ habitId: treadmill });
    const voided = await service.voidEntry(entry.id);
    expect(voided.voidedAt?.epochMs).toBe(clock.now().epochMs);
  });

  it('refuses to void the same entry twice', async () => {
    const { entry } = await service.record({ habitId: treadmill });
    await service.voidEntry(entry.id);
    await expect(service.voidEntry(entry.id)).rejects.toThrow(ConflictError);
  });

  it('amends a reading', async () => {
    const { entry } = await service.record({ habitId: treadmill, reading: 20 });
    expect((await service.amend(entry.id, 25)).reading).toBe(25);
  });

  it('complains about an id it does not hold', async () => {
    await expect(service.amend(checkInId('chk_9999999999999999'), 1)).rejects.toThrow(
      NotFoundError,
    );
  });
});

describe('listing', () => {
  beforeEach(async () => {
    await service.record({ habitId: treadmill, at: '2025-01-08T06:00:00Z' });
    await service.record({ habitId: treadmill, at: '2025-01-10T06:00:00Z' });
  });

  it('resolves each entry onto its rotation day', async () => {
    const found = await service.listFor(aMember().id);
    expect(found.map((item) => formatCivilDate(item.day.date))).toEqual([
      '2025-01-08',
      '2025-01-10',
    ]);
  });

  it('narrows to a window', async () => {
    const found = await service.listFor(aMember().id, parseDateRange('2025-01-09..2025-01-11'));
    expect(found).toHaveLength(1);
  });

  it('leaves voided entries out', async () => {
    const rows = await store.checkIns.byMember(aMember().id);
    await service.voidEntry(rows[0]!.id);
    expect(await service.listFor(aMember().id)).toHaveLength(1);
  });

  it('complains about a member it does not hold', async () => {
    await expect(service.listFor(aMember({ id: 'mem_9999999999999999' }).id)).rejects.toThrow(
      NotFoundError,
    );
  });
});

describe('validation passes through', () => {
  it('rejects a negative reading', async () => {
    await expect(service.record({ habitId: treadmill, reading: -1 })).rejects.toThrow(
      ValidationError,
    );
  });
});
