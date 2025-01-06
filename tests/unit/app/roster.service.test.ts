import { beforeEach, describe, expect, it } from 'vitest';
import {
  describeRosterDay,
  rosterService,
  type RosterService,
} from '../../../src/app/roster.service.js';
import { memoryStore, type MemoryStore } from '../../../src/infra/store/memory.js';
import { fixedClock } from '../../../src/infra/clock.js';
import { sequentialIds } from '../../../src/infra/id.js';
import { crewMemberId } from '../../../src/domain/ids.js';
import { formatDateRange } from '../../../src/domain/time/range.js';
import { ConflictError, NotFoundError } from '../../../src/domain/errors.js';
import { aMember } from '../../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const bo = crewMemberId('mem_FEDCBA9876543210');
let store: MemoryStore;
let service: RosterService;

beforeEach(async () => {
  store = memoryStore({ clock: fixedClock('2025-01-10T09:00:00Z'), ids: sequentialIds() });
  service = rosterService(store);
  await store.members.save(aMember());
});

describe('reading a roster', () => {
  it('assembles the member with their overrides', async () => {
    await service.hold({
      memberId: ada,
      range: '2025-01-20..2025-01-22',
      role: 'onshift',
      reason: 'held_over',
    });
    const target = await service.rosterFor(ada);
    expect(target.member.id).toBe(ada);
    expect(target.overrides).toHaveLength(1);
  });

  it('complains about a member it does not hold', async () => {
    await expect(service.rosterFor(bo)).rejects.toThrow(NotFoundError);
  });
});

describe('days over a window', () => {
  it('gives a role and a zone for each day', async () => {
    const days = await service.daysFor(ada, '2025-01-19..2025-01-21');
    expect(days.map((day) => day.role)).toEqual(['onshift', 'offshift', 'offshift']);
    expect(days.map((day) => day.zone)).toEqual(['Europe/Oslo', 'Europe/Lisbon', 'Europe/Lisbon']);
  });

  it('says where a role came from', async () => {
    await service.hold({
      memberId: ada,
      range: '2025-01-20..2025-01-22',
      role: 'onshift',
      reason: 'held_over',
    });
    const days = await service.daysFor(ada, '2025-01-19..2025-01-21');
    expect(days.map((day) => day.source)).toEqual(['pattern', 'override', 'override']);
    expect(describeRosterDay(days[1]!)).toBe('2025-01-20 onshift (override)');
  });
});

describe('hitches', () => {
  it('finds the hitch a date sits in', async () => {
    const range = await service.hitchOn(ada, '2025-01-30');
    expect(formatDateRange(range)).toBe('2025-01-06..2025-02-02');
  });
});

describe('holding somebody over', () => {
  it('stores the override with the clock reading', async () => {
    const entry = await service.hold({
      memberId: ada,
      range: '2025-01-20..2025-01-22',
      role: 'onshift',
      reason: 'held_over',
      note: 'relief weathered off',
    });
    expect(entry.enteredAt.epochMs).toBe(store.clock.now().epochMs);
    expect(entry.note).toBe('relief weathered off');
    expect(await store.overrides.byMember(ada)).toHaveLength(1);
  });

  it('refuses an entry that clashes with one already stored', async () => {
    await service.hold({
      memberId: ada,
      range: '2025-01-20..2025-01-22',
      role: 'onshift',
      reason: 'held_over',
    });
    await expect(
      service.hold({
        memberId: ada,
        range: '2025-01-22..2025-01-25',
        role: 'offshift',
        reason: 'medical',
      }),
    ).rejects.toThrow(ConflictError);
  });

  it('leaves the store untouched when it refuses', async () => {
    await service.hold({
      memberId: ada,
      range: '2025-01-20..2025-01-22',
      role: 'onshift',
      reason: 'held_over',
    });
    await expect(
      service.hold({
        memberId: ada,
        range: '2025-01-21..2025-01-25',
        role: 'offshift',
        reason: 'medical',
      }),
    ).rejects.toThrow();
    expect(await store.overrides.byMember(ada)).toHaveLength(1);
  });
});

describe('shielding days', () => {
  it('stores a self declared shield', async () => {
    const entry = await service.shieldDays({
      memberId: ada,
      range: '2025-01-20..2025-01-26',
      reason: 'annual_leave',
    });
    expect(entry.grantedBy).toBeUndefined();
    expect(await store.shields.byMember(ada)).toHaveLength(1);
  });

  it('needs a granter for the other reasons', async () => {
    await expect(
      service.shieldDays({ memberId: ada, range: '2025-02-03..2025-02-07', reason: 'training' }),
    ).rejects.toThrow(/is needed to shield days/);
  });
});

describe('reassigning a rotation', () => {
  it('replaces the pattern and stores the member', async () => {
    const updated = await service.reassignRotation({
      memberId: ada,
      pattern: '2-2-3',
      anchor: '2025-03-03',
    });
    expect(updated.rotation.pattern.cycle).toHaveLength(14);
    expect((await store.members.byId(ada))?.rotation.pattern.cycle).toHaveLength(14);
  });

  it('takes a phase for a back to back crew', async () => {
    const updated = await service.reassignRotation({
      memberId: ada,
      pattern: '14/14',
      anchor: '2025-03-03',
      phase: 14,
    });
    expect(updated.rotation.phase).toBe(14);
  });

  it('passes a bad pattern straight back', async () => {
    await expect(
      service.reassignRotation({ memberId: ada, pattern: 'two weeks on', anchor: '2025-03-03' }),
    ).rejects.toThrow(/cannot read the segment/);
  });
});
