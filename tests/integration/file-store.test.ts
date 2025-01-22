import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileStore, type FileStore } from '../../src/infra/store/file.js';
import { fixedClock } from '../../src/infra/clock.js';
import { sequentialIds } from '../../src/infra/id.js';
import { habitsService } from '../../src/app/habits.service.js';
import { checkInsService } from '../../src/app/checkins.service.js';
import { rosterService } from '../../src/app/roster.service.js';
import { adherenceService } from '../../src/app/adherence.service.js';
import { crewService } from '../../src/app/crew.service.js';
import { nudgesService } from '../../src/app/nudges.service.js';
import { crewMemberId } from '../../src/domain/ids.js';
import { aMember } from '../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
let directory: string;
let path: string;
let store: FileStore;

beforeEach(async () => {
  directory = await mkdtemp(join(tmpdir(), 'hitchline-store-'));
  path = join(directory, 'hitchline.jsonl');
  store = await fileStore({
    path,
    clock: fixedClock('2025-01-10T09:00:00Z'),
    ids: sequentialIds(),
  });
});

afterEach(async () => {
  await store.close();
  await rm(directory, { recursive: true, force: true });
});

async function reopen(): Promise<FileStore> {
  await store.close();
  store = await fileStore({
    path,
    clock: fixedClock('2025-01-10T09:00:00Z'),
    ids: sequentialIds(),
  });
  return store;
}

describe('writing and reloading', () => {
  it('survives a close and reopen', async () => {
    await store.members.save(aMember());
    const habits = habitsService(store);
    const created = await habits.create({
      memberId: ada,
      name: 'Twenty minutes on the treadmill',
      cadence: '1/onshift-day',
      startsOn: '2025-01-06',
    });

    const reopened = await reopen();
    expect((await reopened.members.byId(ada))?.displayName).toBe('Ada Vasquez');
    expect((await reopened.habits.byId(created.id))?.name).toBe('Twenty minutes on the treadmill');
  });

  it('writes one line per change', async () => {
    await store.members.save(aMember());
    await store.members.save(aMember({ displayName: 'Ada Nkemdirim' }));
    const text = await readFile(path, 'utf8');
    expect(text.trim().split('\n')).toHaveLength(2);
  });

  it('keeps the last write for a record', async () => {
    await store.members.save(aMember());
    await store.members.save(aMember({ displayName: 'Ada Nkemdirim' }));
    const reopened = await reopen();
    expect((await reopened.members.byId(ada))?.displayName).toBe('Ada Nkemdirim');
    expect(await reopened.members.all()).toHaveLength(1);
  });

  it('starts empty on a fresh path', async () => {
    expect(await store.members.all()).toEqual([]);
    expect(await store.crews.all()).toEqual([]);
  });
});

describe('a full run through the services', () => {
  beforeEach(async () => {
    await store.members.save(aMember());
  });

  it('records check ins and reports on them after a reload', async () => {
    const habits = habitsService(store);
    const created = await habits.create({
      memberId: ada,
      name: 'Twenty minutes on the treadmill',
      cadence: '1/onshift-day',
      startsOn: '2025-01-06',
    });
    const checkIns = checkInsService(store);
    await checkIns.record({ habitId: created.id, at: '2025-01-06T07:00:00Z' });
    await checkIns.record({ habitId: created.id, at: '2025-01-07T07:00:00Z' });

    const reopened = await reopen();
    const report = await adherenceService(reopened).report(ada, '2025-01-06..2025-01-09');
    expect(report.summary.owed).toBe(4);
    expect(report.summary.met).toBe(2);
  });

  it('keeps overrides and shields across a reload', async () => {
    const rosters = rosterService(store);
    await rosters.hold({
      memberId: ada,
      range: '2025-01-20..2025-01-22',
      role: 'onshift',
      reason: 'held_over',
      note: 'relief weathered off',
    });
    await rosters.shieldDays({
      memberId: ada,
      range: '2025-02-03..2025-02-05',
      reason: 'annual_leave',
    });

    const reopened = await reopen();
    const overrides = await reopened.overrides.byMember(ada);
    const shields = await reopened.shields.byMember(ada);
    expect(overrides).toHaveLength(1);
    expect(overrides[0]?.note).toBe('relief weathered off');
    expect(shields).toHaveLength(1);
  });

  it('keeps a voided check in as voided', async () => {
    const habits = habitsService(store);
    const created = await habits.create({
      memberId: ada,
      name: 'Row',
      cadence: '1/day',
      startsOn: '2025-01-06',
    });
    const checkIns = checkInsService(store);
    const { entry } = await checkIns.record({ habitId: created.id, at: '2025-01-08T07:00:00Z' });
    await checkIns.voidEntry(entry.id);

    const reopened = await reopen();
    expect((await reopened.checkIns.byId(entry.id))?.voidedAt).toBeDefined();
    expect(await checkInsService(reopened).listFor(ada)).toEqual([]);
  });

  it('keeps crews, memberships and sharing across a reload', async () => {
    const crews = crewService(store);
    const alpha = await crews.create({
      name: 'Alpha production',
      site: 'Grane B',
      siteZone: 'Europe/Oslo',
    });
    await crews.enrol({ crewId: alpha.id, memberId: ada, from: '2025-01-01' });
    await crews.setVisibility(ada, 'crew');

    const reopened = await reopen();
    const back = crewService(reopened);
    expect((await back.crewFor(ada, '2025-02-01'))?.name).toBe('Alpha production');
    expect((await back.visibilityFor(ada)).fallback).toBe('crew');
  });

  it('keeps a nudge policy across a reload', async () => {
    await nudgesService(store).setPolicy(ada, { maxPerDay: 1, quietHours: '23:00-07:00' });
    const reopened = await reopen();
    const policy = await nudgesService(reopened).policyFor(ada);
    expect(policy.maxPerDay).toBe(1);
    expect(policy.quietHours.to.hour).toBe(7);
  });

  it('ends a membership in place rather than adding a second row', async () => {
    const crews = crewService(store);
    const alpha = await crews.create({
      name: 'Alpha production',
      site: 'Grane B',
      siteZone: 'Europe/Oslo',
    });
    await crews.enrol({ crewId: alpha.id, memberId: ada, from: '2025-01-01' });
    await crews.release(alpha.id, ada, '2025-06-30');

    const reopened = await reopen();
    const rows = await reopened.crews.memberships();
    expect(rows).toHaveLength(1);
    expect(rows[0]?.to?.month).toBe(6);
  });
});

describe('reload on demand', () => {
  it('picks up what another writer appended', async () => {
    await store.members.save(aMember());
    const second = await fileStore({ path, clock: fixedClock('2025-01-10T10:00:00Z') });
    await second.members.save(aMember({ id: 'mem_FEDCBA9876543210', displayName: 'Bo Lindqvist' }));
    await second.close();

    expect(await store.members.all()).toHaveLength(1);
    await store.reload();
    expect(await store.members.all()).toHaveLength(2);
  });
});
