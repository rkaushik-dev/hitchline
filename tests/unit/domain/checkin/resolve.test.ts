import { describe, expect, it } from 'vitest';
import {
  countInRange,
  daysTouched,
  groupByDay,
  inRange,
  onDate,
  readingsInRange,
  resolveAll,
  resolveCheckIn,
  straddling,
} from '../../../../src/domain/checkin/resolve.js';
import { checkIn, type CheckIn } from '../../../../src/domain/checkin/checkin.js';
import { checkInId, crewMemberId, habitId } from '../../../../src/domain/ids.js';
import { formatCivilDate, parseCivilDate } from '../../../../src/domain/time/civil.js';
import { parseDateRange } from '../../../../src/domain/time/range.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';
import { aMember } from '../../../support/fixtures.js';

const member = aMember();
let next = 0;

function at(iso: string, reading?: number): CheckIn {
  next += 1;
  return checkIn({
    id: checkInId(`chk_${next.toString().padStart(16, '0')}`),
    habitId: habitId('hab_0123456789ABCDEF'),
    memberId: crewMemberId('mem_0123456789ABCDEF'),
    at: parseInstant(iso),
    recordedAt: parseInstant(iso),
    source: 'app',
    reading,
  });
}

describe('resolving one check in', () => {
  it('puts an on shift entry on the site day', () => {
    const resolved = resolveCheckIn(member, at('2025-01-10T09:00:00Z'));
    expect(formatCivilDate(resolved.day.date)).toBe('2025-01-10');
    expect(resolved.day.zone).toBe('Europe/Oslo');
  });

  it('puts an off shift entry on the home day', () => {
    const resolved = resolveCheckIn(member, at('2025-01-25T23:30:00Z'));
    expect(formatCivilDate(resolved.day.date)).toBe('2025-01-25');
    expect(resolved.day.zone).toBe('Europe/Lisbon');
  });

  it('keeps the check in alongside the day', () => {
    const entry = at('2025-01-10T09:00:00Z', 25);
    expect(resolveCheckIn(member, entry).checkIn).toBe(entry);
  });
});

describe('grouping', () => {
  const resolved = resolveAll(member, [
    at('2025-01-10T06:00:00Z', 20),
    at('2025-01-10T18:00:00Z', 30),
    at('2025-01-12T06:00:00Z'),
  ]);

  it('collects entries by the day they landed on', () => {
    const groups = groupByDay(resolved);
    expect(groups.get('2025-01-10')).toHaveLength(2);
    expect(groups.get('2025-01-12')).toHaveLength(1);
    expect(groups.get('2025-01-11')).toBeUndefined();
  });

  it('lists the days in order', () => {
    expect(daysTouched(resolved)).toEqual(['2025-01-10', '2025-01-12']);
  });

  it('filters to one date', () => {
    expect(onDate(resolved, parseCivilDate('2025-01-10'))).toHaveLength(2);
    expect(onDate(resolved, parseCivilDate('2025-01-11'))).toEqual([]);
  });
});

describe('windows', () => {
  const resolved = resolveAll(member, [
    at('2025-01-10T06:00:00Z', 20),
    at('2025-01-12T06:00:00Z', 30),
    at('2025-01-25T12:00:00Z', 40),
  ]);

  it('counts what falls inside', () => {
    expect(countInRange(resolved, parseDateRange('2025-01-06..2025-01-19'))).toBe(2);
    expect(countInRange(resolved, parseDateRange('2025-01-20..2025-02-02'))).toBe(1);
  });

  it('is closed at both ends', () => {
    expect(countInRange(resolved, parseDateRange('2025-01-10..2025-01-12'))).toBe(2);
    expect(countInRange(resolved, parseDateRange('2025-01-11..2025-01-11'))).toBe(0);
  });

  it('returns the entries themselves', () => {
    const found = inRange(resolved, parseDateRange('2025-01-10..2025-01-10'));
    expect(found[0]?.checkIn.reading).toBe(20);
  });

  it('collects readings and skips entries without one', () => {
    const mixed = resolveAll(member, [at('2025-01-10T06:00:00Z', 20), at('2025-01-10T18:00:00Z')]);
    expect(readingsInRange(mixed, parseDateRange('2025-01-10..2025-01-10'))).toEqual([20]);
  });
});

describe('straddled days', () => {
  it('picks out the entries the two clocks disagreed about', () => {
    const resolved = resolveAll(member, [
      at('2025-01-10T09:00:00Z'),
      at('2025-01-25T23:30:00Z'),
      at('2025-01-19T23:30:00Z'),
    ]);
    const odd = straddling(resolved);
    expect(odd).toHaveLength(2);
    expect(odd.map((item) => formatCivilDate(item.day.date)).sort()).toEqual([
      '2025-01-20',
      '2025-01-25',
    ]);
  });

  it('finds none when home and site share a clock', () => {
    const local = aMember({ homeZone: 'Europe/Oslo', siteZone: 'Europe/Oslo' });
    const resolved = resolveAll(local, [at('2025-01-25T23:30:00Z')]);
    expect(straddling(resolved)).toEqual([]);
  });
});
