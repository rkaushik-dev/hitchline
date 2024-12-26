import { describe, expect, it } from 'vitest';
import { fixedClock, nowToSecond, systemClock, tickingClock } from '../../../src/infra/clock.js';
import { randomIds, scriptedIds, sequentialIds } from '../../../src/infra/id.js';
import { formatInstant, parseInstant } from '../../../src/domain/time/zone.js';
import { isWellFormed } from '../../../src/domain/ids.js';

describe('the system clock', () => {
  it('reads something close to now', () => {
    const before = Date.now();
    const reading = systemClock().now().epochMs;
    expect(reading).toBeGreaterThanOrEqual(before);
    expect(reading).toBeLessThan(before + 5_000);
  });
});

describe('a fixed clock', () => {
  it('reads the same instant every time', () => {
    const clock = fixedClock('2025-01-10T09:00:00Z');
    expect(formatInstant(clock.now())).toBe('2025-01-10T09:00:00.000Z');
    expect(formatInstant(clock.now())).toBe('2025-01-10T09:00:00.000Z');
  });

  it('takes an instant as well as a string', () => {
    const clock = fixedClock(parseInstant('2025-01-10T09:00:00Z'));
    expect(clock.now().epochMs).toBe(parseInstant('2025-01-10T09:00:00Z').epochMs);
  });

  it('moves when told to', () => {
    const clock = fixedClock('2025-01-10T09:00:00Z');
    clock.advance(3_600_000);
    expect(formatInstant(clock.now())).toBe('2025-01-10T10:00:00.000Z');
    clock.set(parseInstant('2025-02-01T00:00:00Z'));
    expect(formatInstant(clock.now())).toBe('2025-02-01T00:00:00.000Z');
  });

  it('refuses a nonsense step', () => {
    expect(() => fixedClock('2025-01-10T09:00:00Z').advance(Number.NaN)).toThrow(/finite number/);
  });
});

describe('a ticking clock', () => {
  it('moves on by the step each reading', () => {
    const clock = tickingClock('2025-01-10T09:00:00Z', 60_000);
    expect(formatInstant(clock.now())).toBe('2025-01-10T09:00:00.000Z');
    expect(formatInstant(clock.now())).toBe('2025-01-10T09:01:00.000Z');
    expect(formatInstant(clock.now())).toBe('2025-01-10T09:02:00.000Z');
  });

  it('defaults to a second', () => {
    const clock = tickingClock('2025-01-10T09:00:00Z');
    clock.now();
    expect(formatInstant(clock.now())).toBe('2025-01-10T09:00:01.000Z');
  });

  it('refuses a step below one', () => {
    expect(() => tickingClock('2025-01-10T09:00:00Z', 0)).toThrow(/above zero/);
  });
});

describe('nowToSecond', () => {
  it('drops the milliseconds', () => {
    const clock = fixedClock(parseInstant('2025-01-10T09:00:00.750Z'));
    expect(formatInstant(nowToSecond(clock))).toBe('2025-01-10T09:00:00.000Z');
  });
});

describe('random ids', () => {
  it('mints ids of the right shape', () => {
    const ids = randomIds();
    const minted = ids.next('HabitId');
    expect(isWellFormed('HabitId', minted)).toBe(true);
    expect(minted.startsWith('hab_')).toBe(true);
  });

  it('does not repeat itself over a run', () => {
    const ids = randomIds();
    const seen = new Set<string>();
    for (let index = 0; index < 200; index += 1) {
      seen.add(ids.next('CheckInId'));
    }
    expect(seen.size).toBe(200);
  });
});

describe('sequential ids', () => {
  it('is stable for a given seed', () => {
    expect(sequentialIds(0).next('HabitId')).toBe(sequentialIds(0).next('HabitId'));
  });

  it('moves on with each call', () => {
    const ids = sequentialIds();
    const first = ids.next('CrewId');
    const second = ids.next('CrewId');
    expect(first).not.toBe(second);
    expect(isWellFormed('CrewId', second)).toBe(true);
  });

  it('keeps the prefix for the kind asked for', () => {
    const ids = sequentialIds();
    expect(ids.next('CrewMemberId').startsWith('mem_')).toBe(true);
    expect(ids.next('CheckInId').startsWith('chk_')).toBe(true);
  });
});

describe('scripted ids', () => {
  it('hands back the list in order', () => {
    const ids = scriptedIds(['hab_0000000000000001', 'hab_0000000000000002']);
    expect(ids.next('HabitId')).toBe('hab_0000000000000001');
    expect(ids.next('HabitId')).toBe('hab_0000000000000002');
  });

  it('refuses once the list runs out', () => {
    const ids = scriptedIds(['hab_0000000000000001']);
    ids.next('HabitId');
    expect(() => ids.next('HabitId')).toThrow(/ran out after 1 of them/);
  });
});
