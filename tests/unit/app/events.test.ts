import { describe, expect, it, vi } from 'vitest';
import {
  createBus,
  describeEvent,
  domainEvent,
  type DomainEvent,
} from '../../../src/app/events.js';
import { checkInId, crewMemberId, habitId } from '../../../src/domain/ids.js';
import { parseInstant } from '../../../src/domain/time/zone.js';
import { ValidationError } from '../../../src/domain/errors.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const treadmill = habitId('hab_0123456789ABCDEF');
const at = parseInstant('2025-05-13T09:00:00Z');

function anEvent(overrides: Partial<Parameters<typeof domainEvent>[0]> = {}): DomainEvent {
  return domainEvent({
    name: 'habit.created',
    at,
    memberId: ada,
    habitId: treadmill,
    ...overrides,
  });
}

describe('building an event', () => {
  it('keeps the subject ids it was given', () => {
    const event = anEvent();
    expect(event.habitId).toBe(treadmill);
    expect(event.checkInId).toBeUndefined();
    expect(event.detail).toBe('');
  });

  it('trims and bounds the detail', () => {
    expect(anEvent({ detail: '  cadence changed  ' }).detail).toBe('cadence changed');
    expect(() => anEvent({ detail: 'x'.repeat(241) })).toThrow(ValidationError);
  });

  it('reads back in one line', () => {
    expect(describeEvent(anEvent({ detail: 'cadence changed' }))).toBe(
      '2025-05-13T09:00:00.000Z habit.created hab_0123456789ABCDEF cadence changed',
    );
  });

  it('falls back to the member when nothing else names it', () => {
    const event = domainEvent({ name: 'crew.enrolled', at, memberId: ada });
    expect(describeEvent(event)).toContain(ada);
  });
});

describe('the bus', () => {
  it('calls a subscriber for its event', async () => {
    const bus = createBus();
    const seen: DomainEvent[] = [];
    bus.on('habit.created', (event) => {
      seen.push(event);
    });
    await bus.publish(anEvent());
    expect(seen).toHaveLength(1);
  });

  it('does not call it for another event', async () => {
    const bus = createBus();
    const heard = vi.fn();
    bus.on('checkin.recorded', heard);
    await bus.publish(anEvent());
    expect(heard).not.toHaveBeenCalled();
  });

  it('calls a wildcard subscriber for everything', async () => {
    const bus = createBus();
    const heard = vi.fn();
    bus.on('*', heard);
    await bus.publish(anEvent());
    await bus.publish(
      domainEvent({
        name: 'checkin.recorded',
        at,
        memberId: ada,
        checkInId: checkInId('chk_0123456789ABCDEF'),
      }),
    );
    expect(heard).toHaveBeenCalledTimes(2);
  });

  it('awaits an async subscriber', async () => {
    const bus = createBus();
    let done = false;
    bus.on('habit.created', async () => {
      await Promise.resolve();
      done = true;
    });
    await bus.publish(anEvent());
    expect(done).toBe(true);
  });

  it('stops calling one that unsubscribed', async () => {
    const bus = createBus();
    const heard = vi.fn();
    const off = bus.on('habit.created', heard);
    off();
    await bus.publish(anEvent());
    expect(heard).not.toHaveBeenCalled();
    expect(bus.listeners).toBe(0);
  });

  it('carries on past a subscriber that throws', async () => {
    const failures: unknown[] = [];
    const bus = createBus((error) => failures.push(error));
    const later = vi.fn();
    bus.on('habit.created', () => {
      throw new Error('audit is down');
    });
    bus.on('habit.created', later);
    await bus.publish(anEvent());
    expect(later).toHaveBeenCalledOnce();
    expect(failures).toHaveLength(1);
  });

  it('counts what is listening', () => {
    const bus = createBus();
    bus.on('habit.created', vi.fn());
    bus.on('*', vi.fn());
    expect(bus.listeners).toBe(2);
  });

  it('publishes to nobody without complaint', async () => {
    await expect(createBus().publish(anEvent())).resolves.toBeUndefined();
  });
});
