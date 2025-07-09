import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileStore, type FileStore } from '../../src/infra/store/file.js';
import { fixedClock, type MutableClock } from '../../src/infra/clock.js';
import { sequentialIds } from '../../src/infra/id.js';
import { createBus } from '../../src/app/events.js';
import { auditEverything, createAuditLog, type AuditLog } from '../../src/app/audit.js';
import { habitsService } from '../../src/app/habits.service.js';
import { checkInsService } from '../../src/app/checkins.service.js';
import { rosterService } from '../../src/app/roster.service.js';
import { adherenceService } from '../../src/app/adherence.service.js';
import { crewService } from '../../src/app/crew.service.js';
import { nudgesService } from '../../src/app/nudges.service.js';
import { compareService } from '../../src/app/compare.service.js';
import { importService } from '../../src/app/import.service.js';
import { digest } from '../../src/app/digest.js';
import { seedDemo } from '../../src/app/seed.js';
import { memberLoader } from '../../src/app/loader.js';
import { checkInsToCsv, resultsToCsv } from '../../src/export/csv.js';
import { crewId, crewMemberId } from '../../src/domain/ids.js';
import { computeStreak } from '../../src/domain/adherence/streak.js';
import { formatCivilDate } from '../../src/domain/time/civil.js';
import type { Ports } from '../../src/app/ports.js';

/**
 * One hitch, start to finish, against the store that actually ships. Every
 * other integration test covers one seam; this one is the whole road, and it
 * is the test that would have caught the two bugs where a service and its
 * repository disagreed about what had been written.
 */
const ada = crewMemberId('mem_0000000000000001');
let directory: string;
let path: string;
let store: FileStore;
let ports: Ports;
let clock: MutableClock;
let log: AuditLog;

beforeEach(async () => {
  directory = await mkdtemp(join(tmpdir(), 'hitchline-lifecycle-'));
  path = join(directory, 'hitchline.jsonl');
  clock = fixedClock('2025-01-20T09:00:00Z');
  store = await fileStore({ path, clock, ids: sequentialIds() });
  const bus = createBus();
  log = createAuditLog();
  auditEverything(bus, log);
  ports = { ...store, events: bus };
});

afterEach(async () => {
  await store.close();
  await rm(directory, { recursive: true, force: true });
});

describe('a hitch from start to finish', () => {
  it('carries one member from enrolment to the end of hitch note', async () => {
    // The crewing office loads the rota and the standard habits.
    const seeded = await seedDemo(ports, { from: '2025-01-06', days: 14, adherence: 1 });
    expect(seeded.members).toBe(5);

    // The medic adds one more habit part way through the hitch.
    const habits = habitsService(ports);
    const extra = await habits.applyTemplate({
      memberId: ada,
      key: 'kit-check',
      startsOn: '2025-01-13',
    });
    expect(extra.name).toBe('Check the survival suit');

    // The person does it, late enough that a supervisor has to record it.
    const checkIns = checkInsService(ports);
    await checkIns.record({
      habitId: extra.id,
      at: '2025-01-14T08:00:00Z',
      source: 'supervisor',
      note: 'suit checked with the medic',
    });

    // Weather holds the relief crew ashore and the hitch runs three days over.
    await rosterService(ports).hold({
      memberId: ada,
      range: '2025-01-20..2025-01-22',
      role: 'onshift',
      reason: 'weather',
      note: 'relief weathered off',
    });

    // The report reads the whole thing, held over days and all.
    clock.set(fixedClock('2025-01-23T09:00:00Z').now());
    const report = await adherenceService(ports).report(ada, '2025-01-06..2025-01-22');
    expect(report.summary.owed).toBeGreaterThan(14);
    expect(report.byRole.some((split) => split.role === 'onshift')).toBe(true);

    // And the note that goes out at the end of it names the habits by name.
    const loaded = await memberLoader(ports).load(ada);
    const streaks = new Map(
      loaded.habits.map((habit) => [
        habit.id as string,
        computeStreak({
          results: memberLoader(ports).scoreOne(loaded, habit, report.window),
          shields: loaded.shields,
          memberId: ada,
        }),
      ]),
    );
    const note = digest({
      member: loaded.roster.member,
      summary: report.summary,
      habits: [...loaded.habits],
      results: report.results,
      streaks,
    });
    expect(note).toContain('Ada Vasquez');
    expect(note).toContain('Check the survival suit');
  });

  it('survives a restart in the middle of the hitch', async () => {
    await seedDemo(ports, { from: '2025-01-06', days: 14, adherence: 1 });
    const before = await adherenceService(ports).report(ada, '2025-01-06..2025-01-19');

    await store.close();
    store = await fileStore({ path, clock, ids: sequentialIds() });
    const after = await adherenceService(store).report(ada, '2025-01-06..2025-01-19');

    expect(after.summary).toEqual(before.summary);
    expect(after.results).toHaveLength(before.results.length);
  });

  it('lets a lead read the crew without seeing what nobody shared', async () => {
    const seeded = await seedDemo(ports, { from: '2025-01-06', days: 14, adherence: 1 });
    const comparison = await compareService(ports).crew(
      ada,
      crewId(seeded.crewId),
      '2025-01-06..2025-01-19',
      '2025-01-10',
    );
    expect(comparison.lines).toHaveLength(5);
    const mine = comparison.lines.find((line) => line.memberId === ada);
    expect(mine?.hidden).toBe(0);
    expect(comparison.lines.filter((line) => line.hidden > 0)).toHaveLength(4);
  });

  it('imports a fortnight of paper records and reports on them', async () => {
    await seedDemo(ports, { from: '2025-01-06', days: 1, adherence: 0 });
    const habit = (await ports.habits.byMember(ada))[0];
    const rows = [
      'habit_id,at,reading,note,idempotency_key',
      `${habit?.id ?? ''},2025-01-07T07:00:00Z,22,,paper-01`,
      `${habit?.id ?? ''},2025-01-08T07:00:00Z,24,,paper-02`,
      `${habit?.id ?? ''},not a time,20,,paper-03`,
    ].join('\n');

    const report = await importService(ports).checkIns(rows, { source: 'supervisor' });
    expect(report.accepted).toBe(2);
    expect(report.failures).toHaveLength(1);

    const again = await importService(ports).checkIns(rows, { source: 'supervisor' });
    expect(again.duplicates).toBe(2);
  });

  it('plans a nudge only for what is still open', async () => {
    await seedDemo(ports, { from: '2025-01-06', days: 14, adherence: 0 });
    clock.set(fixedClock('2025-01-15T06:00:00Z').now());
    const plan = await nudgesService(ports).plan(ada, '2025-01-15..2025-01-19');
    expect(plan.length).toBeGreaterThan(0);
    for (const nudge of plan) {
      expect(nudge.at.epochMs).toBeGreaterThanOrEqual(clock.now().epochMs);
    }
  });

  it('exports what a spreadsheet asks for', async () => {
    await seedDemo(ports, { from: '2025-01-06', days: 14, adherence: 1 });
    const resolved = await checkInsService(ports).listFor(ada);
    const csv = checkInsToCsv(resolved);
    expect(csv.split('\r\n')[0]).toContain('rotation_day');
    expect(csv.split('\r\n').length).toBeGreaterThan(2);

    const report = await adherenceService(ports).report(ada, '2025-01-06..2025-01-19');
    expect(resultsToCsv(report.results).split('\r\n')[0]).toContain('due_on');
  });

  it('writes an audit line for everything anybody did', async () => {
    const habits = habitsService(ports);
    await seedDemo(ports, { from: '2025-01-06', days: 1, adherence: 0 });
    const created = await habits.create({
      memberId: ada,
      name: 'Row the ergometer',
      cadence: '1/day',
      startsOn: '2025-01-06',
    });
    await habits.changeCadence(created.id, '3/hitch');
    await habits.moveTo(created.id, 'archived');

    const names = log.forMember(ada).map((entry) => entry.name);
    expect(names).toEqual(['habit.created', 'habit.changed', 'habit.archived']);
  });

  it('keeps the crew readable a year later', async () => {
    const seeded = await seedDemo(ports, { from: '2025-01-06', days: 14, adherence: 1 });
    const members = await crewService(ports).membersOfCrew(crewId(seeded.crewId), '2025-12-31');
    expect(members).toHaveLength(5);
    expect(formatCivilDate(seeded.to)).toBe('2025-01-19');
  });
});
