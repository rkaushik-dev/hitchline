import { beforeEach, describe, expect, it } from 'vitest';
import { memoryStore, type MemoryStore } from '../../../../src/infra/store/memory.js';
import { fixedClock } from '../../../../src/infra/clock.js';
import { sequentialIds } from '../../../../src/infra/id.js';
import { checkIn } from '../../../../src/domain/checkin/checkin.js';
import { crew, membership } from '../../../../src/domain/crew/crew.js';
import { sharingPreference } from '../../../../src/domain/crew/sharing.js';
import { nudgePolicy } from '../../../../src/domain/nudge/policy.js';
import { rosterOverride } from '../../../../src/domain/rotation/override.js';
import { shield } from '../../../../src/domain/adherence/shield.js';
import { checkInId, crewId, crewMemberId, habitId } from '../../../../src/domain/ids.js';
import { parseCivilDate } from '../../../../src/domain/time/civil.js';
import { parseDateRange } from '../../../../src/domain/time/range.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';
import { ConflictError, NotFoundError } from '../../../../src/domain/errors.js';
import { aHabit, aMember } from '../../../support/fixtures.js';

const d = parseCivilDate;
const r = parseDateRange;
const ada = crewMemberId('mem_0123456789ABCDEF');
const bo = crewMemberId('mem_FEDCBA9876543210');
const alpha = crewId('crw_0123456789ABCDEF');

let store: MemoryStore;

beforeEach(() => {
  store = memoryStore({
    clock: fixedClock('2025-01-10T09:00:00Z'),
    ids: sequentialIds(),
  });
});

function anEntry(id: string, at: string, habit = 'hab_0123456789ABCDEF') {
  return checkIn({
    id: checkInId(id),
    habitId: habitId(habit),
    memberId: ada,
    at: parseInstant(at),
    recordedAt: parseInstant(at),
    source: 'app',
  });
}

describe('members', () => {
  it('saves and reads back', async () => {
    await store.members.save(aMember());
    expect((await store.members.byId(ada))?.displayName).toBe('Ada Vasquez');
  });

  it('answers nothing for an id it does not hold', async () => {
    expect(await store.members.byId(bo)).toBeUndefined();
  });

  it('overwrites on a second save', async () => {
    await store.members.save(aMember());
    await store.members.save(aMember({ displayName: 'Ada Nkemdirim' }));
    expect(await store.members.all()).toHaveLength(1);
  });
});

describe('habits', () => {
  it('filters to one member', async () => {
    await store.habits.save(aHabit());
    await store.habits.save(aHabit({ id: 'hab_FEDCBA9876543210', memberId: bo }));
    expect(await store.habits.byMember(ada)).toHaveLength(1);
    expect(await store.habits.byMember(bo)).toHaveLength(1);
  });
});

describe('check ins', () => {
  it('refuses the same id twice', async () => {
    await store.checkIns.add(anEntry('chk_0000000000000001', '2025-01-10T07:00:00Z'));
    await expect(
      store.checkIns.add(anEntry('chk_0000000000000001', '2025-01-10T07:00:00Z')),
    ).rejects.toThrow(ConflictError);
  });

  it('replaces one it holds', async () => {
    const entry = anEntry('chk_0000000000000001', '2025-01-10T07:00:00Z');
    await store.checkIns.add(entry);
    await store.checkIns.replace({ ...entry, note: 'amended' });
    expect((await store.checkIns.byId(checkInId('chk_0000000000000001')))?.note).toBe('amended');
  });

  it('refuses to replace one it does not hold', async () => {
    await expect(
      store.checkIns.replace(anEntry('chk_0000000000000009', '2025-01-10T07:00:00Z')),
    ).rejects.toThrow(NotFoundError);
  });

  it('filters by habit', async () => {
    await store.checkIns.add(anEntry('chk_0000000000000001', '2025-01-10T07:00:00Z'));
    await store.checkIns.add(
      anEntry('chk_0000000000000002', '2025-01-10T08:00:00Z', 'hab_FEDCBA9876543210'),
    );
    expect(await store.checkIns.byHabit(habitId('hab_0123456789ABCDEF'))).toHaveLength(1);
  });

  it('filters by window when one is given', async () => {
    await store.members.save(aMember());
    await store.checkIns.add(anEntry('chk_0000000000000001', '2025-01-10T07:00:00Z'));
    await store.checkIns.add(anEntry('chk_0000000000000002', '2025-01-20T07:00:00Z'));
    expect(await store.checkIns.byMember(ada, r('2025-01-01..2025-01-15'))).toHaveLength(1);
    expect(await store.checkIns.byMember(ada)).toHaveLength(2);
  });
});

describe('overrides and shields', () => {
  it('keeps each person entries apart', async () => {
    await store.overrides.add(
      rosterOverride({
        memberId: ada,
        range: r('2025-01-20..2025-01-22'),
        role: 'onshift',
        reason: 'held_over',
        enteredAt: parseInstant('2025-01-18T10:00:00Z'),
      }),
    );
    await store.shields.add(
      shield({
        memberId: bo,
        range: r('2025-01-20..2025-01-26'),
        reason: 'annual_leave',
        grantedAt: parseInstant('2025-01-04T10:00:00Z'),
      }),
    );
    expect(await store.overrides.byMember(ada)).toHaveLength(1);
    expect(await store.overrides.byMember(bo)).toEqual([]);
    expect(await store.shields.byMember(bo)).toHaveLength(1);
  });
});

describe('crews', () => {
  it('saves a crew and its memberships', async () => {
    await store.crews.save(
      crew({ id: alpha, name: 'Alpha production', site: 'Grane B', siteZone: 'Europe/Oslo' }),
    );
    await store.crews.addMembership(
      membership({ crewId: alpha, memberId: ada, from: d('2025-01-01') }),
    );
    expect((await store.crews.byId(alpha))?.site).toBe('Grane B');
    expect(await store.crews.memberships()).toHaveLength(1);
  });

  it('replaces a membership in place', async () => {
    const entry = membership({ crewId: alpha, memberId: ada, from: d('2025-01-01') });
    await store.crews.addMembership(entry);
    await store.crews.replaceMembership({ ...entry, to: d('2025-06-30') });
    const rows = await store.crews.memberships();
    expect(rows[0]?.to).toBeDefined();
  });

  it('refuses to replace a membership it does not hold', async () => {
    const entry = membership({ crewId: alpha, memberId: bo, from: d('2025-01-01') });
    await expect(store.crews.replaceMembership(entry)).rejects.toThrow(NotFoundError);
  });
});

describe('sharing and policies', () => {
  it('stores one row per member', async () => {
    await store.sharing.save(sharingPreference({ memberId: ada, fallback: 'crew' }));
    await store.policies.save(ada, nudgePolicy({ maxPerDay: 1 }));
    expect((await store.sharing.byMember(ada))?.fallback).toBe('crew');
    expect((await store.policies.byMember(ada))?.maxPerDay).toBe(1);
    expect(await store.policies.byMember(bo)).toBeUndefined();
  });
});

describe('reset', () => {
  it('empties everything', async () => {
    await store.members.save(aMember());
    await store.habits.save(aHabit());
    await store.checkIns.add(anEntry('chk_0000000000000001', '2025-01-10T07:00:00Z'));
    store.reset();
    expect(await store.members.all()).toEqual([]);
    expect(await store.habits.byMember(ada)).toEqual([]);
    expect(await store.checkIns.byMember(ada)).toEqual([]);
  });
});

describe('the supplied clock and ids', () => {
  it('are the ones handed in', () => {
    expect(store.clock.now().epochMs).toBe(parseInstant('2025-01-10T09:00:00Z').epochMs);
    expect(store.ids.next('HabitId').startsWith('hab_')).toBe(true);
  });
});
