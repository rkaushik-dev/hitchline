import { beforeEach, describe, expect, it } from 'vitest';
import { adherenceService, type AdherenceService } from '../../../src/app/adherence.service.js';
import { checkInsService, type CheckInsService } from '../../../src/app/checkins.service.js';
import { rosterService, type RosterService } from '../../../src/app/roster.service.js';
import { memoryStore, type MemoryStore } from '../../../src/infra/store/memory.js';
import { fixedClock, type MutableClock } from '../../../src/infra/clock.js';
import { sequentialIds } from '../../../src/infra/id.js';
import { crewMemberId, habitId } from '../../../src/domain/ids.js';
import { formatDateRange } from '../../../src/domain/time/range.js';
import { NotFoundError } from '../../../src/domain/errors.js';
import { aHabit, aMember } from '../../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const treadmill = habitId('hab_0123456789ABCDEF');
let store: MemoryStore;
let service: AdherenceService;
let checkIns: CheckInsService;
let rosters: RosterService;
let clock: MutableClock;

beforeEach(async () => {
  clock = fixedClock('2025-01-20T09:00:00Z');
  store = memoryStore({ clock, ids: sequentialIds() });
  service = adherenceService(store);
  checkIns = checkInsService(store);
  rosters = rosterService(store);
  await store.members.save(aMember());
  await store.habits.save(aHabit({ cadence: '1/onshift-day', startsOn: '2025-01-06' }));
});

async function checkInOn(day: string): Promise<void> {
  await checkIns.record({ habitId: treadmill, at: `${day}T09:00:00Z` });
}

describe('a report over a hitch', () => {
  it('owes one obligation for each shift day', async () => {
    const report = await service.report(ada, '2025-01-06..2025-01-19');
    expect(report.summary.owed).toBe(14);
  });

  it('owes nothing over a fortnight at home', async () => {
    const report = await service.report(ada, '2025-01-20..2025-02-02');
    expect(report.summary.owed).toBe(0);
  });

  it('counts what was done', async () => {
    await checkInOn('2025-01-06');
    await checkInOn('2025-01-07');
    const report = await service.report(ada, '2025-01-06..2025-01-19');
    expect(report.summary.met).toBe(2);
    expect(report.summary.missed).toBe(12);
  });

  it('rates the settled days', async () => {
    await checkInOn('2025-01-06');
    const report = await service.report(ada, '2025-01-06..2025-01-19');
    expect(report.summary.rate).toBeCloseTo(1 / 14);
  });

  it('reports the window and the day it was run', async () => {
    const report = await service.report(ada, '2025-01-06..2025-01-19');
    expect(formatDateRange(report.window)).toBe('2025-01-06..2025-01-19');
    expect(report.asOf.day).toBe(20);
  });

  it('splits the window by habit and by hitch', async () => {
    await store.habits.save(
      aHabit({ id: 'hab_FEDCBA9876543210', cadence: '1/hitch', startsOn: '2025-01-06' }),
    );
    const report = await service.report(ada, '2025-01-06..2025-02-02');
    expect(report.byHabit).toHaveLength(2);
    expect(report.byHitch).toHaveLength(1);
  });

  it('complains about a member it does not hold', async () => {
    await expect(
      service.report(crewMemberId('mem_9999999999999999'), '2025-01-06..2025-01-19'),
    ).rejects.toThrow(NotFoundError);
  });
});

describe('overrides and shields feed the report', () => {
  it('adds the days an override held somebody over', async () => {
    await rosters.hold({
      memberId: ada,
      range: '2025-01-20..2025-01-22',
      role: 'onshift',
      reason: 'held_over',
    });
    const report = await service.report(ada, '2025-01-20..2025-02-02');
    expect(report.summary.owed).toBe(3);
  });

  it('counts shielded days in the summary', async () => {
    await rosters.shieldDays({
      memberId: ada,
      range: '2025-01-13..2025-01-15',
      reason: 'annual_leave',
    });
    const report = await service.report(ada, '2025-01-06..2025-01-19');
    expect(report.summary.shieldedDays).toBe(3);
  });
});

describe('streaks', () => {
  it('counts an unbroken run of shift days', async () => {
    for (const day of ['2025-01-06', '2025-01-07', '2025-01-08']) {
      await checkInOn(day);
    }
    const summary = await service.streak(ada, treadmill, '2025-01-06..2025-01-08');
    expect(summary.current).toBe(3);
  });

  it('survives a shielded gap', async () => {
    for (const day of ['2025-01-12', '2025-01-16']) {
      await checkInOn(day);
    }
    await rosters.shieldDays({
      memberId: ada,
      range: '2025-01-13..2025-01-15',
      reason: 'annual_leave',
    });
    const summary = await service.streak(ada, treadmill, '2025-01-12..2025-01-16');
    expect(summary.current).toBe(2);
    expect(summary.shielded).toBe(3);
  });

  it('complains about a habit the member does not have', async () => {
    await expect(
      service.streak(ada, habitId('hab_9999999999999999'), '2025-01-06..2025-01-19'),
    ).rejects.toThrow(NotFoundError);
  });
});

describe('what is still open', () => {
  it('lists only the pending obligations with nothing done', async () => {
    clock.set(store.clock.now());
    const open = await service.open(ada, '2025-01-06..2025-01-31');
    expect(open.every((result) => result.status === 'pending')).toBe(true);
  });

  it('drops one that has been done', async () => {
    const before = await service.open(ada, '2025-01-20..2025-01-31');
    await rosters.hold({
      memberId: ada,
      range: '2025-01-20..2025-01-22',
      role: 'onshift',
      reason: 'held_over',
    });
    const after = await service.open(ada, '2025-01-20..2025-01-31');
    expect(after.length).toBeGreaterThan(before.length);
  });
});

describe('the shape of a report', () => {
  it('splits the results by the role of the day', async () => {
    await checkInOn('2025-01-06');
    const report = await service.report(ada, '2025-01-06..2025-01-19');
    expect(report.byRole.map((split) => split.role)).toEqual(['onshift']);
    expect(report.byRole[0]?.owed).toBe(14);
  });

  it('says it cannot read a trend from one hitch', async () => {
    const report = await service.report(ada, '2025-01-06..2025-01-19');
    expect(report.trend).toBe('unknown');
  });

  it('names the busiest hour once something has been recorded', async () => {
    await checkInOn('2025-01-06');
    await checkInOn('2025-01-07');
    const report = await service.report(ada, '2025-01-06..2025-01-19');
    expect(report.busiestHour).toBe(10);
  });

  it('has no busiest hour when nothing has been recorded', async () => {
    const report = await service.report(ada, '2025-01-06..2025-01-19');
    expect(report.busiestHour).toBeUndefined();
  });
});
