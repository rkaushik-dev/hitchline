import { beforeEach, describe, expect, it } from 'vitest';
import { seedDemo } from '../../../src/app/seed.js';
import { adherenceService } from '../../../src/app/adherence.service.js';
import { crewService } from '../../../src/app/crew.service.js';
import { memoryStore, type MemoryStore } from '../../../src/infra/store/memory.js';
import { fixedClock } from '../../../src/infra/clock.js';
import { sequentialIds } from '../../../src/infra/id.js';
import { crewId, crewMemberId } from '../../../src/domain/ids.js';
import { formatCivilDate } from '../../../src/domain/time/civil.js';

let store: MemoryStore;

beforeEach(() => {
  store = memoryStore({ clock: fixedClock('2025-02-10T09:00:00Z'), ids: sequentialIds() });
});

describe('seeding a demo', () => {
  it('builds a crew with everybody in it', async () => {
    const report = await seedDemo(store);
    expect(report.members).toBe(5);
    expect(await store.members.all()).toHaveLength(5);
    expect(await store.crews.memberships()).toHaveLength(5);
  });

  it('gives everybody the same set of habits', async () => {
    const report = await seedDemo(store);
    expect(report.habits).toBe(20);
    const first = await store.habits.byMember(crewMemberId('mem_0000000000000001'));
    expect(first).toHaveLength(4);
  });

  it('records a fortnight of history by default', async () => {
    const report = await seedDemo(store);
    expect(formatCivilDate(report.from)).toBe('2025-01-06');
    expect(formatCivilDate(report.to)).toBe('2025-02-02');
    expect(report.checkIns).toBeGreaterThan(0);
  });

  it('is the same twice for the same seed', async () => {
    const first = await seedDemo(store);
    const second = await seedDemo(memoryStore({ clock: store.clock, ids: sequentialIds() }));
    expect(second.checkIns).toBe(first.checkIns);
  });

  it('does more when told to be more diligent', async () => {
    const lazy = await seedDemo(store, { adherence: 0.2 });
    const keen = await seedDemo(memoryStore({ clock: store.clock, ids: sequentialIds() }), {
      adherence: 1,
    });
    expect(keen.checkIns).toBeGreaterThan(lazy.checkIns);
  });

  it('takes a start date and a length', async () => {
    const report = await seedDemo(store, { from: '2025-03-03', days: 7 });
    expect(formatCivilDate(report.from)).toBe('2025-03-03');
    expect(formatCivilDate(report.to)).toBe('2025-03-09');
  });

  it('puts the crew lead first', async () => {
    const report = await seedDemo(store);
    const rows = await store.crews.memberships();
    expect(rows[0]?.role).toBe('lead');
    expect(report.crewId.startsWith('crw_')).toBe(true);
  });

  it('only records shift day habits on shift days', async () => {
    await seedDemo(store, { adherence: 1 });
    const report = await adherenceService(store).report(
      crewMemberId('mem_0000000000000001'),
      '2025-01-06..2025-02-02',
    );
    expect(report.summary.missed).toBe(0);
  });

  it('leaves the crew readable through the crew service', async () => {
    const report = await seedDemo(store);
    const members = await crewService(store).membersOfCrew(crewId(report.crewId), '2025-01-10');
    expect(members).toHaveLength(5);
  });
});
