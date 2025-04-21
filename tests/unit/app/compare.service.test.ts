import { beforeEach, describe, expect, it } from 'vitest';
import { compareService, type CompareService } from '../../../src/app/compare.service.js';
import { crewService } from '../../../src/app/crew.service.js';
import { checkInsService } from '../../../src/app/checkins.service.js';
import { memoryStore, type MemoryStore } from '../../../src/infra/store/memory.js';
import { fixedClock } from '../../../src/infra/clock.js';
import { sequentialIds } from '../../../src/infra/id.js';
import { crewId, crewMemberId, habitId } from '../../../src/domain/ids.js';
import { NotFoundError } from '../../../src/domain/errors.js';
import { aHabit, aMember } from '../../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const bo = crewMemberId('mem_FEDCBA9876543210');
const treadmill = habitId('hab_0123456789ABCDEF');
const stretches = habitId('hab_FEDCBA9876543210');

let store: MemoryStore;
let service: CompareService;
let alpha: string;

beforeEach(async () => {
  store = memoryStore({ clock: fixedClock('2025-01-20T09:00:00Z'), ids: sequentialIds() });
  service = compareService(store);
  await store.members.save(aMember());
  await store.members.save(aMember({ id: 'mem_FEDCBA9876543210', displayName: 'Bo Lindqvist' }));
  await store.habits.save(aHabit({ cadence: '1/onshift-day', startsOn: '2025-01-06' }));
  await store.habits.save(
    aHabit({
      id: 'hab_FEDCBA9876543210',
      memberId: 'mem_FEDCBA9876543210',
      cadence: '1/onshift-day',
      startsOn: '2025-01-06',
    }),
  );

  const crews = crewService(store);
  const crew = await crews.create({
    name: 'Alpha production',
    site: 'Grane B',
    siteZone: 'Europe/Oslo',
  });
  alpha = crew.id;
  await crews.enrol({ crewId: crew.id, memberId: ada, from: '2025-01-01', role: 'lead' });
  await crews.enrol({ crewId: crew.id, memberId: bo, from: '2025-01-01' });

  const checkIns = checkInsService(store);
  await checkIns.record({ habitId: treadmill, at: '2025-01-06T09:00:00Z', source: 'supervisor' });
  await checkIns.record({ habitId: treadmill, at: '2025-01-07T09:00:00Z', source: 'supervisor' });
});

const window = '2025-01-06..2025-01-19';

describe('reading your own crew', () => {
  it('lists everybody in it on the date', async () => {
    const report = await service.crew(ada, crewId(alpha), window, '2025-01-20');
    expect(report.lines.map((line) => line.displayName)).toContain('Ada Vasquez');
    expect(report.lines).toHaveLength(2);
  });

  it('counts your own habits whatever the sharing says', async () => {
    const report = await service.crew(ada, crewId(alpha), window, '2025-01-20');
    const mine = report.lines.find((line) => line.memberId === ada);
    expect(mine?.owed).toBe(14);
    expect(mine?.met).toBe(2);
    expect(mine?.hidden).toBe(0);
  });

  it('hides a habit somebody has not shared', async () => {
    const report = await service.crew(ada, crewId(alpha), window, '2025-01-20');
    const theirs = report.lines.find((line) => line.memberId === bo);
    expect(theirs?.owed).toBe(0);
    expect(theirs?.hidden).toBe(1);
  });

  it('counts a habit once it is shared with the lead', async () => {
    await crewService(store).setHabitVisibility(bo, stretches, 'lead_only');
    const report = await service.crew(ada, crewId(alpha), window, '2025-01-20');
    const theirs = report.lines.find((line) => line.memberId === bo);
    expect(theirs?.owed).toBe(14);
    expect(theirs?.hidden).toBe(0);
  });

  it('still hides it from a crewmate who is not the lead', async () => {
    await crewService(store).setHabitVisibility(ada, treadmill, 'lead_only');
    const report = await service.crew(bo, crewId(alpha), window, '2025-01-20');
    const theirs = report.lines.find((line) => line.memberId === ada);
    expect(theirs?.hidden).toBe(1);
  });
});

describe('the crew rate', () => {
  it('is the share of what the viewer can see that landed', async () => {
    const report = await service.crew(ada, crewId(alpha), window, '2025-01-20');
    expect(report.rate).toBeCloseTo(2 / 14);
  });

  it('counts only what the viewer can see', async () => {
    // Bo sees their own habit and nothing of Ada's, so the crew rate is Bo's.
    const report = await service.crew(bo, crewId(alpha), window, '2025-01-20');
    expect(report.rate).toBe(0);
    expect(report.lines.find((line) => line.memberId === ada)?.owed).toBe(0);
  });

  it('reads a window with nothing owed as clean rather than as zero', async () => {
    const report = await service.crew(ada, crewId(alpha), '2024-01-01..2024-01-31', '2025-01-20');
    expect(report.rate).toBe(1);
  });

  it('orders the best first', async () => {
    await crewService(store).setVisibility(bo, 'crew');
    const report = await service.crew(ada, crewId(alpha), window, '2025-01-20');
    expect(report.lines[0]?.memberId).toBe(ada);
  });
});

describe('bad requests', () => {
  it('complains about a crew it does not hold', async () => {
    await expect(
      service.crew(ada, crewId('crw_9999999999999999'), window, '2025-01-20'),
    ).rejects.toThrow(NotFoundError);
  });

  it('reads an empty crew on a date nobody was in it', async () => {
    const report = await service.crew(ada, crewId(alpha), window, '2024-01-01');
    expect(report.lines).toEqual([]);
    expect(report.rate).toBe(1);
  });
});
