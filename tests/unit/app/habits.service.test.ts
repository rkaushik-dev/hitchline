import { beforeEach, describe, expect, it } from 'vitest';
import { habitsService, type HabitsService } from '../../../src/app/habits.service.js';
import { memoryStore, type MemoryStore } from '../../../src/infra/store/memory.js';
import { fixedClock } from '../../../src/infra/clock.js';
import { sequentialIds } from '../../../src/infra/id.js';
import { moveTo } from '../../../src/domain/crew/member.js';
import { formatCadence } from '../../../src/domain/habit/cadence.js';
import { formatTarget } from '../../../src/domain/habit/target.js';
import { crewMemberId, habitId } from '../../../src/domain/ids.js';
import { formatCivilDate } from '../../../src/domain/time/civil.js';
import { NotFoundError, ValidationError } from '../../../src/domain/errors.js';
import { aMember } from '../../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
let store: MemoryStore;
let service: HabitsService;

beforeEach(async () => {
  store = memoryStore({ clock: fixedClock('2025-01-10T09:00:00Z'), ids: sequentialIds() });
  service = habitsService(store);
  await store.members.save(aMember());
});

describe('creating a habit', () => {
  it('mints an id and stores it', async () => {
    const created = await service.create({
      memberId: ada,
      name: 'Twenty minutes on the treadmill',
      cadence: '1/onshift-day',
    });
    expect(created.id.startsWith('hab_')).toBe(true);
    expect(await store.habits.byId(created.id)).toEqual(created);
  });

  it('starts today in the member home zone when no date is given', async () => {
    const created = await service.create({ memberId: ada, name: 'Stretch', cadence: '1/day' });
    expect(formatCivilDate(created.startsOn)).toBe('2025-01-10');
  });

  it('takes the dates it is given', async () => {
    const created = await service.create({
      memberId: ada,
      name: 'Stretch',
      cadence: '1/day',
      startsOn: '2025-02-01',
      endsOn: '2025-03-31',
    });
    expect(formatCivilDate(created.startsOn)).toBe('2025-02-01');
    expect(created.endsOn && formatCivilDate(created.endsOn)).toBe('2025-03-31');
  });

  it('parses the cadence and target', async () => {
    const created = await service.create({
      memberId: ada,
      name: 'Row',
      cadence: '3/hitch',
      target: 'at least 20 minutes',
    });
    expect(formatCadence(created.cadence)).toBe('3/hitch');
    expect(created.target && formatTarget(created.target)).toBe('at least 20 minutes');
  });

  it('refuses a member it has never heard of', async () => {
    await expect(
      service.create({
        memberId: crewMemberId('mem_FEDCBA9876543210'),
        name: 'Stretch',
        cadence: '1/day',
      }),
    ).rejects.toThrow(NotFoundError);
  });

  it('refuses a member who has left', async () => {
    await store.members.save(moveTo(aMember(), 'departed'));
    await expect(
      service.create({ memberId: ada, name: 'Stretch', cadence: '1/day' }),
    ).rejects.toThrow(ValidationError);
  });

  it('passes a bad cadence straight back', async () => {
    await expect(
      service.create({ memberId: ada, name: 'Stretch', cadence: 'every day' }),
    ).rejects.toThrow(/must look like 3\/hitch/);
  });

  it('stamps the moment it was created', async () => {
    const created = await service.create({ memberId: ada, name: 'Stretch', cadence: '1/day' });
    expect(created.createdAt.epochMs).toBe(store.clock.now().epochMs);
  });
});

describe('reading habits', () => {
  beforeEach(async () => {
    await service.create({ memberId: ada, name: 'Row the ergometer', cadence: '1/day' });
    await service.create({ memberId: ada, name: 'Bank the sleep hours', cadence: '1/day' });
  });

  it('lists a member habits by name', async () => {
    const found = await service.listFor(ada);
    expect(found.map((entry) => entry.name)).toEqual(['Bank the sleep hours', 'Row the ergometer']);
  });

  it('gets one by id', async () => {
    const [first] = await service.listFor(ada);
    expect((await service.get(first!.id)).name).toBe(first!.name);
  });

  it('complains about an id it does not hold', async () => {
    await expect(service.get(habitId('hab_9999999999999999'))).rejects.toThrow(NotFoundError);
  });

  it('gives an empty list for somebody with none', async () => {
    expect(await service.listFor(crewMemberId('mem_FEDCBA9876543210'))).toEqual([]);
  });
});

describe('editing a habit', () => {
  it('renames and stores', async () => {
    const created = await service.create({ memberId: ada, name: 'Row', cadence: '1/day' });
    await service.rename(created.id, 'Row the ergometer');
    expect((await store.habits.byId(created.id))?.name).toBe('Row the ergometer');
  });

  it('pauses and resumes', async () => {
    const created = await service.create({ memberId: ada, name: 'Row', cadence: '1/day' });
    expect((await service.moveTo(created.id, 'paused')).status).toBe('paused');
    expect((await service.moveTo(created.id, 'active')).status).toBe('active');
  });

  it('changes the cadence', async () => {
    const created = await service.create({ memberId: ada, name: 'Row', cadence: '1/day' });
    const changed = await service.changeCadence(created.id, '2/onshift-day');
    expect(formatCadence(changed.cadence)).toBe('2/onshift-day');
  });

  it('sets and clears the target', async () => {
    const created = await service.create({ memberId: ada, name: 'Row', cadence: '1/day' });
    expect((await service.changeTarget(created.id, 'at least 5 km')).target?.amount).toBe(5);
    expect((await service.changeTarget(created.id, undefined)).target).toBeUndefined();
  });

  it('replaces the tags', async () => {
    const created = await service.create({ memberId: ada, name: 'Row', cadence: '1/day' });
    expect((await service.changeTags(created.id, ['Fitness', 'cardio'])).tags).toEqual([
      'cardio',
      'fitness',
    ]);
  });

  it('refuses to edit an archived habit', async () => {
    const created = await service.create({ memberId: ada, name: 'Row', cadence: '1/day' });
    await service.moveTo(created.id, 'archived');
    await expect(service.changeCadence(created.id, '3/hitch')).rejects.toThrow(/archived habit/);
  });
});
