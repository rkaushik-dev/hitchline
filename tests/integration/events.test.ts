import { beforeEach, describe, expect, it } from 'vitest';
import { createBus, type DomainEvent } from '../../src/app/events.js';
import { auditEverything, createAuditLog, type AuditLog } from '../../src/app/audit.js';
import { habitsService } from '../../src/app/habits.service.js';
import { checkInsService } from '../../src/app/checkins.service.js';
import { rosterService } from '../../src/app/roster.service.js';
import { memoryStore, type MemoryStore } from '../../src/infra/store/memory.js';
import { fixedClock } from '../../src/infra/clock.js';
import { sequentialIds } from '../../src/infra/id.js';
import { crewMemberId, type HabitId } from '../../src/domain/ids.js';
import type { Ports } from '../../src/app/ports.js';
import { aMember } from '../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
let store: MemoryStore;
let ports: Ports;
let heard: DomainEvent[];
let log: AuditLog;

beforeEach(async () => {
  store = memoryStore({ clock: fixedClock('2025-05-16T09:00:00Z'), ids: sequentialIds() });
  const bus = createBus();
  heard = [];
  bus.on('*', (event) => {
    heard.push(event);
  });
  log = createAuditLog();
  auditEverything(bus, log);
  ports = { ...store, events: bus };
  await store.members.save(aMember());
});

async function aNewHabit(): Promise<HabitId> {
  const created = await habitsService(ports).create({
    memberId: ada,
    name: 'Twenty minutes on the treadmill',
    cadence: '1/day',
    startsOn: '2025-05-01',
  });
  return created.id;
}

describe('habits', () => {
  it('announces a new one', async () => {
    await aNewHabit();
    expect(heard.map((event) => event.name)).toEqual(['habit.created']);
    expect(heard[0]?.detail).toBe('Twenty minutes on the treadmill');
  });

  it('announces a change with what changed', async () => {
    const id = await aNewHabit();
    heard.length = 0;
    await habitsService(ports).changeCadence(id, '3/hitch');
    expect(heard[0]?.name).toBe('habit.changed');
    expect(heard[0]?.detail).toBe('cadence 3/hitch');
  });

  it('announces an archive apart from an ordinary change', async () => {
    const id = await aNewHabit();
    heard.length = 0;
    await habitsService(ports).moveTo(id, 'archived');
    expect(heard[0]?.name).toBe('habit.archived');
  });
});

describe('check ins', () => {
  it('announces one that lands', async () => {
    const id = await aNewHabit();
    heard.length = 0;
    await checkInsService(ports).record({ habitId: id, source: 'app' });
    expect(heard[0]?.name).toBe('checkin.recorded');
    expect(heard[0]?.checkInId).toBeDefined();
  });

  it('says nothing when an idempotency key repeats', async () => {
    const id = await aNewHabit();
    const command = { habitId: id, idempotencyKey: 'watch-0516' };
    await checkInsService(ports).record(command);
    heard.length = 0;
    await checkInsService(ports).record(command);
    expect(heard).toEqual([]);
  });

  it('announces a void', async () => {
    const id = await aNewHabit();
    const { entry } = await checkInsService(ports).record({ habitId: id });
    heard.length = 0;
    await checkInsService(ports).voidEntry(entry.id);
    expect(heard[0]?.name).toBe('checkin.voided');
  });
});

describe('the roster', () => {
  it('announces a hold and a shield', async () => {
    await rosterService(ports).hold({
      memberId: ada,
      range: '2025-05-20..2025-05-22',
      role: 'onshift',
      reason: 'held_over',
    });
    await rosterService(ports).shieldDays({
      memberId: ada,
      range: '2025-06-02..2025-06-06',
      reason: 'annual_leave',
    });
    expect(heard.map((event) => event.name)).toEqual(['roster.held', 'shield.granted']);
  });
});

describe('the audit log', () => {
  it('collects everything the services announced', async () => {
    const id = await aNewHabit();
    await checkInsService(ports).record({ habitId: id });
    expect(log.size).toBe(2);
    expect(log.forMember(ada)).toHaveLength(2);
  });
});

describe('a store with no bus', () => {
  it('works exactly as before', async () => {
    const created = await habitsService(store).create({
      memberId: ada,
      name: 'Row the ergometer',
      cadence: '1/day',
    });
    expect(created.id.startsWith('hab_')).toBe(true);
    expect(heard).toEqual([]);
  });
});
