import { beforeEach, describe, expect, it } from 'vitest';
import { nudgesService, type NudgesService } from '../../../src/app/nudges.service.js';
import { checkInsService, type CheckInsService } from '../../../src/app/checkins.service.js';
import { memoryStore, type MemoryStore } from '../../../src/infra/store/memory.js';
import { fixedClock } from '../../../src/infra/clock.js';
import { sequentialIds } from '../../../src/infra/id.js';
import { DEFAULT_POLICY } from '../../../src/domain/nudge/policy.js';
import { crewMemberId, habitId } from '../../../src/domain/ids.js';
import { formatInstant } from '../../../src/domain/time/zone.js';
import { NotFoundError } from '../../../src/domain/errors.js';
import { aHabit, aMember } from '../../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const nobody = crewMemberId('mem_9999999999999999');
const treadmill = habitId('hab_0123456789ABCDEF');
let store: MemoryStore;
let service: NudgesService;
let checkIns: CheckInsService;

beforeEach(async () => {
  store = memoryStore({ clock: fixedClock('2025-01-10T06:00:00Z'), ids: sequentialIds() });
  service = nudgesService(store);
  checkIns = checkInsService(store);
  await store.members.save(aMember());
  await store.habits.save(aHabit({ cadence: '1/onshift-day', startsOn: '2025-01-06' }));
});

describe('policies', () => {
  it('falls back to the default when none is stored', async () => {
    expect(await service.policyFor(ada)).toEqual(DEFAULT_POLICY);
  });

  it('stores one and reads it back', async () => {
    await service.setPolicy(ada, { maxPerDay: 1, quietHours: '23:00-07:00' });
    const stored = await service.policyFor(ada);
    expect(stored.maxPerDay).toBe(1);
    expect(stored.quietHours.to.hour).toBe(7);
  });

  it('validates on the way in', async () => {
    await expect(service.setPolicy(ada, { maxPerDay: 99 })).rejects.toThrow(/between 0 and 12/);
  });

  it('complains about a member it does not hold', async () => {
    await expect(service.setPolicy(nobody, {})).rejects.toThrow(NotFoundError);
  });
});

describe('planning', () => {
  it('plans one nudge for the open day', async () => {
    const plan = await service.plan(ada, '2025-01-10..2025-01-10');
    expect(plan).toHaveLength(1);
    expect(formatInstant(plan[0]!.at)).toBe('2025-01-10T19:00:00.000Z');
  });

  it('plans nothing once the day is done', async () => {
    await checkIns.record({ habitId: treadmill, at: '2025-01-10T05:00:00Z' });
    expect(await service.plan(ada, '2025-01-10..2025-01-10')).toEqual([]);
  });

  it('plans nothing for a fortnight at home', async () => {
    expect(await service.plan(ada, '2025-01-20..2025-02-02')).toEqual([]);
  });

  it('honours a stored policy', async () => {
    await service.setPolicy(ada, { maxPerDay: 0 });
    expect(await service.plan(ada, '2025-01-10..2025-01-19')).toEqual([]);
  });

  it('caps a run of days at the daily limit', async () => {
    await service.setPolicy(ada, { maxPerDay: 1, leadMinutes: 240 });
    const plan = await service.plan(ada, '2025-01-10..2025-01-12');
    expect(plan).toHaveLength(3);
  });

  it('complains about a member it does not hold', async () => {
    await expect(service.plan(nobody, '2025-01-10..2025-01-10')).rejects.toThrow(NotFoundError);
  });
});

describe('risk', () => {
  it('is not at risk with no run going', async () => {
    expect(await service.atRisk(ada, '2025-01-06..2025-01-19')).toBe(false);
  });

  it('is at risk once a run is alive and today is open', async () => {
    await checkIns.record({ habitId: treadmill, at: '2025-01-08T09:00:00Z' });
    await checkIns.record({ habitId: treadmill, at: '2025-01-09T09:00:00Z' });
    expect(await service.atRisk(ada, '2025-01-08..2025-01-10')).toBe(true);
  });

  it('is not at risk once today is done', async () => {
    await checkIns.record({ habitId: treadmill, at: '2025-01-08T09:00:00Z' });
    await checkIns.record({ habitId: treadmill, at: '2025-01-09T09:00:00Z' });
    await checkIns.record({ habitId: treadmill, at: '2025-01-10T05:00:00Z' });
    expect(await service.atRisk(ada, '2025-01-08..2025-01-10')).toBe(false);
  });
});
