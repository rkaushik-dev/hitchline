import { describe, expect, it } from 'vitest';
import { auditEverything, createAuditLog } from '../../../src/app/audit.js';
import { createBus, domainEvent, type EventName } from '../../../src/app/events.js';
import { crewMemberId, habitId } from '../../../src/domain/ids.js';
import { parseDateRange } from '../../../src/domain/time/range.js';
import { parseInstant } from '../../../src/domain/time/zone.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const bo = crewMemberId('mem_FEDCBA9876543210');
const treadmill = habitId('hab_0123456789ABCDEF');

function anEvent(name: EventName, at: string, memberId = ada) {
  return domainEvent({ name, at: parseInstant(at), memberId, habitId: treadmill });
}

describe('recording', () => {
  it('numbers entries from one', () => {
    const log = createAuditLog();
    const first = log.record(anEvent('habit.created', '2025-05-14T09:00:00Z'));
    const second = log.record(anEvent('habit.changed', '2025-05-14T10:00:00Z'));
    expect(first.sequence).toBe(1);
    expect(second.sequence).toBe(2);
    expect(log.size).toBe(2);
  });

  it('keeps everything the event carried', () => {
    const log = createAuditLog();
    const entry = log.record(anEvent('habit.created', '2025-05-14T09:00:00Z'));
    expect(entry.habitId).toBe(treadmill);
    expect(entry.memberId).toBe(ada);
  });

  it('drops the oldest once it is full', () => {
    const log = createAuditLog({ keep: 3 });
    for (let index = 0; index < 5; index += 1) {
      log.record(anEvent('habit.changed', '2025-05-14T09:00:00Z'));
    }
    expect(log.size).toBe(3);
    expect(log.all()[0]?.sequence).toBe(3);
  });
});

describe('reading it back', () => {
  const log = createAuditLog();
  log.record(anEvent('habit.created', '2025-05-14T09:00:00Z'));
  log.record(anEvent('checkin.recorded', '2025-05-15T09:00:00Z'));
  log.record(anEvent('habit.changed', '2025-06-01T09:00:00Z', bo));

  it('filters to one member', () => {
    expect(log.forMember(ada)).toHaveLength(2);
    expect(log.forMember(bo)).toHaveLength(1);
  });

  it('filters to one kind of event', () => {
    expect(log.named('checkin.recorded')).toHaveLength(1);
    expect(log.named('crew.enrolled')).toEqual([]);
  });

  it('filters to a window in a zone', () => {
    const inMay = log.inWindow(parseDateRange('2025-05-01..2025-05-31'), 'UTC');
    expect(inMay).toHaveLength(2);
  });

  it('reads the window in the zone it was given', () => {
    const late = createAuditLog();
    late.record(anEvent('habit.created', '2025-05-31T23:30:00Z'));
    expect(late.inWindow(parseDateRange('2025-05-01..2025-05-31'), 'UTC')).toHaveLength(1);
    expect(late.inWindow(parseDateRange('2025-05-01..2025-05-31'), 'Europe/Oslo')).toEqual([]);
  });
});

describe('wiring it to the bus', () => {
  it('records everything that is published', async () => {
    const bus = createBus();
    const log = createAuditLog();
    auditEverything(bus, log);
    await bus.publish(anEvent('habit.created', '2025-05-14T09:00:00Z'));
    await bus.publish(anEvent('checkin.recorded', '2025-05-14T10:00:00Z'));
    expect(log.size).toBe(2);
  });

  it('stops recording once unsubscribed', async () => {
    const bus = createBus();
    const log = createAuditLog();
    const off = auditEverything(bus, log);
    off();
    await bus.publish(anEvent('habit.created', '2025-05-14T09:00:00Z'));
    expect(log.size).toBe(0);
  });
});
