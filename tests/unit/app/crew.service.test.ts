import { beforeEach, describe, expect, it } from 'vitest';
import { crewService, type CrewService } from '../../../src/app/crew.service.js';
import { memoryStore, type MemoryStore } from '../../../src/infra/store/memory.js';
import { fixedClock } from '../../../src/infra/clock.js';
import { sequentialIds } from '../../../src/infra/id.js';
import { crewId, crewMemberId, habitId } from '../../../src/domain/ids.js';
import { ConflictError, NotFoundError } from '../../../src/domain/errors.js';
import { aMember } from '../../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const bo = crewMemberId('mem_FEDCBA9876543210');
const treadmill = habitId('hab_0123456789ABCDEF');
let store: MemoryStore;
let service: CrewService;

beforeEach(async () => {
  store = memoryStore({ clock: fixedClock('2025-01-10T09:00:00Z'), ids: sequentialIds() });
  service = crewService(store);
  await store.members.save(aMember());
  await store.members.save(aMember({ id: 'mem_FEDCBA9876543210', displayName: 'Bo Lindqvist' }));
});

async function alpha() {
  return service.create({ name: 'Alpha production', site: 'Grane B', siteZone: 'Europe/Oslo' });
}

describe('creating a crew', () => {
  it('mints an id and stores it', async () => {
    const created = await alpha();
    expect(created.id.startsWith('crw_')).toBe(true);
    expect(await store.crews.byId(created.id)).toEqual(created);
  });

  it('validates the site', async () => {
    await expect(
      service.create({ name: 'Alpha', site: 'Grane/B', siteZone: 'Europe/Oslo' }),
    ).rejects.toThrow(/not a usable site name/);
  });
});

describe('enrolling', () => {
  it('adds a membership', async () => {
    const crew = await alpha();
    const entry = await service.enrol({ crewId: crew.id, memberId: ada, from: '2025-01-01' });
    expect(entry.role).toBe('member');
    expect(await store.crews.memberships()).toHaveLength(1);
  });

  it('takes a role', async () => {
    const crew = await alpha();
    const entry = await service.enrol({
      crewId: crew.id,
      memberId: bo,
      from: '2025-01-01',
      role: 'lead',
    });
    expect(entry.role).toBe('lead');
  });

  it('refuses somebody already in another crew that day', async () => {
    const first = await alpha();
    const second = await service.create({
      name: 'Bravo production',
      site: 'Grane C',
      siteZone: 'Europe/Oslo',
    });
    await service.enrol({ crewId: first.id, memberId: ada, from: '2025-01-01' });
    await expect(
      service.enrol({ crewId: second.id, memberId: ada, from: '2025-02-01' }),
    ).rejects.toThrow(ConflictError);
  });

  it('refuses a crew it does not hold', async () => {
    await expect(
      service.enrol({ crewId: crewId('crw_9999999999999999'), memberId: ada, from: '2025-01-01' }),
    ).rejects.toThrow(NotFoundError);
  });

  it('refuses a member it does not hold', async () => {
    const crew = await alpha();
    await expect(
      service.enrol({
        crewId: crew.id,
        memberId: crewMemberId('mem_9999999999999999'),
        from: '2025-01-01',
      }),
    ).rejects.toThrow(NotFoundError);
  });
});

describe('releasing', () => {
  it('ends the membership on a date', async () => {
    const crew = await alpha();
    await service.enrol({ crewId: crew.id, memberId: ada, from: '2025-01-01' });
    const ended = await service.release(crew.id, ada, '2025-06-30');
    expect(ended.to?.month).toBe(6);
  });

  it('refuses a membership it does not hold', async () => {
    const crew = await alpha();
    await expect(service.release(crew.id, ada, '2025-06-30')).rejects.toThrow(NotFoundError);
  });
});

describe('reading a crew', () => {
  it('lists who was in it on a date, by name', async () => {
    const crew = await alpha();
    await service.enrol({ crewId: crew.id, memberId: ada, from: '2025-01-01' });
    await service.enrol({ crewId: crew.id, memberId: bo, from: '2025-01-01', role: 'lead' });
    const found = await service.membersOfCrew(crew.id, '2025-02-01');
    expect(found.map((member) => member.displayName)).toEqual(['Ada Vasquez', 'Bo Lindqvist']);
  });

  it('drops somebody once released', async () => {
    const crew = await alpha();
    await service.enrol({ crewId: crew.id, memberId: ada, from: '2025-01-01' });
    await service.release(crew.id, ada, '2025-01-31');
    expect(await service.membersOfCrew(crew.id, '2025-02-01')).toEqual([]);
  });

  it('finds the crew somebody was in', async () => {
    const crew = await alpha();
    await service.enrol({ crewId: crew.id, memberId: ada, from: '2025-01-01' });
    expect((await service.crewFor(ada, '2025-02-01'))?.name).toBe('Alpha production');
    expect(await service.crewFor(ada, '2024-02-01')).toBeUndefined();
  });
});

describe('visibility', () => {
  it('defaults to private', async () => {
    expect((await service.visibilityFor(ada)).fallback).toBe('private');
  });

  it('stores a fallback and keeps per habit settings', async () => {
    await service.setHabitVisibility(ada, treadmill, 'crew');
    const updated = await service.setVisibility(ada, 'lead_only');
    expect(updated.fallback).toBe('lead_only');
    expect(updated.perHabit.get(treadmill)).toBe('crew');
  });

  it('refuses a member it does not hold', async () => {
    await expect(
      service.setVisibility(crewMemberId('mem_9999999999999999'), 'crew'),
    ).rejects.toThrow(NotFoundError);
  });
});

describe('who may read', () => {
  beforeEach(async () => {
    const crew = await alpha();
    await service.enrol({ crewId: crew.id, memberId: ada, from: '2025-01-01' });
    await service.enrol({ crewId: crew.id, memberId: bo, from: '2025-01-01', role: 'lead' });
  });

  it('keeps a private habit to its owner', async () => {
    expect(await service.mayRead(bo, ada, treadmill, '2025-02-01')).toBe(false);
    expect(await service.mayRead(ada, ada, treadmill, '2025-02-01')).toBe(true);
  });

  it('opens a lead only habit to the lead', async () => {
    await service.setHabitVisibility(ada, treadmill, 'lead_only');
    expect(await service.mayRead(bo, ada, treadmill, '2025-02-01')).toBe(true);
  });

  it('opens a crew habit to a crewmate', async () => {
    await service.setVisibility(ada, 'crew');
    expect(await service.mayRead(bo, ada, treadmill, '2025-02-01')).toBe(true);
  });
});
