import { describe, expect, it } from 'vitest';
import {
  adherenceRate,
  countByStatus,
  evaluateAll,
  evaluateObligation,
  isSettled,
} from '../../../../src/domain/adherence/match.js';
import { resolveAll } from '../../../../src/domain/checkin/resolve.js';
import { checkIn, type CheckIn } from '../../../../src/domain/checkin/checkin.js';
import { expandHabit } from '../../../../src/domain/schedule/expand.js';
import { roster } from '../../../../src/domain/rotation/roster.js';
import { checkInId, crewMemberId, habitId } from '../../../../src/domain/ids.js';
import { parseCivilDate } from '../../../../src/domain/time/civil.js';
import { parseDateRange } from '../../../../src/domain/time/range.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';
import { aHabit, aMember } from '../../../support/fixtures.js';

const d = parseCivilDate;
const r = parseDateRange;
const member = aMember();
const plain = roster(member);
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

function obligationsFor(cadence: string, window: string, target?: string) {
  const habit = aHabit({ cadence, target });
  return { habit, entries: expandHabit({ roster: plain, habit, window: r(window) }) };
}

describe('a daily obligation', () => {
  const { habit, entries } = obligationsFor('1/day', '2025-01-10..2025-01-10');

  it('is met by one check in', () => {
    const resolved = resolveAll(member, [at('2025-01-10T09:00:00Z')]);
    const result = evaluateObligation({
      habit,
      obligation: entries[0]!,
      resolved,
      asOf: d('2025-01-11'),
    });
    expect(result.status).toBe('met');
    expect(result.count).toBe(1);
    expect(result.progress).toBe(1);
  });

  it('is missed when the day passed with nothing', () => {
    const result = evaluateObligation({
      habit,
      obligation: entries[0]!,
      resolved: [],
      asOf: d('2025-01-11'),
    });
    expect(result.status).toBe('missed');
    expect(result.progress).toBe(0);
  });

  it('is pending while the day is still open', () => {
    const result = evaluateObligation({
      habit,
      obligation: entries[0]!,
      resolved: [],
      asOf: d('2025-01-10'),
    });
    expect(result.status).toBe('pending');
  });

  it('ignores a check in for another habit', () => {
    const other = resolveAll(member, [at('2025-01-10T09:00:00Z')]).map((item) => ({
      ...item,
      checkIn: { ...item.checkIn, habitId: habitId('hab_FEDCBA9876543210') },
    }));
    const result = evaluateObligation({
      habit,
      obligation: entries[0]!,
      resolved: other,
      asOf: d('2025-01-11'),
    });
    expect(result.count).toBe(0);
  });
});

describe('an obligation asking for several times', () => {
  const { habit, entries } = obligationsFor('3/hitch', '2025-01-06..2025-02-02');

  it('is partial when the window closed short', () => {
    const resolved = resolveAll(member, [at('2025-01-08T09:00:00Z'), at('2025-01-09T09:00:00Z')]);
    const result = evaluateObligation({
      habit,
      obligation: entries[0]!,
      resolved,
      asOf: d('2025-02-03'),
    });
    expect(result.status).toBe('partial');
    expect(result.count).toBe(2);
    expect(result.progress).toBeCloseTo(2 / 3);
  });

  it('is met once the count is reached anywhere in the window', () => {
    const resolved = resolveAll(member, [
      at('2025-01-08T09:00:00Z'),
      at('2025-01-09T09:00:00Z'),
      at('2025-01-30T09:00:00Z'),
    ]);
    const result = evaluateObligation({
      habit,
      obligation: entries[0]!,
      resolved,
      asOf: d('2025-02-03'),
    });
    expect(result.status).toBe('met');
  });

  it('does not count a check in from outside the window', () => {
    const resolved = resolveAll(member, [at('2025-02-04T09:00:00Z')]);
    const result = evaluateObligation({
      habit,
      obligation: entries[0]!,
      resolved,
      asOf: d('2025-02-05'),
    });
    expect(result.count).toBe(0);
  });
});

describe('a targeted obligation', () => {
  const { habit, entries } = obligationsFor(
    '1/day',
    '2025-01-10..2025-01-10',
    'at least 30 minutes',
  );

  it('adds the readings across the window', () => {
    const resolved = resolveAll(member, [
      at('2025-01-10T09:00:00Z', 20),
      at('2025-01-10T18:00:00Z', 15),
    ]);
    const result = evaluateObligation({
      habit,
      obligation: entries[0]!,
      resolved,
      asOf: d('2025-01-11'),
    });
    expect(result.total).toBe(35);
    expect(result.status).toBe('met');
  });

  it('is partial when the count is there but the amount is not', () => {
    const resolved = resolveAll(member, [at('2025-01-10T09:00:00Z', 10)]);
    const result = evaluateObligation({
      habit,
      obligation: entries[0]!,
      resolved,
      asOf: d('2025-01-11'),
    });
    expect(result.status).toBe('partial');
    expect(result.progress).toBeCloseTo(1 / 3);
  });

  it('treats a check in with no reading as nothing towards the target', () => {
    const resolved = resolveAll(member, [at('2025-01-10T09:00:00Z')]);
    const result = evaluateObligation({
      habit,
      obligation: entries[0]!,
      resolved,
      asOf: d('2025-01-11'),
    });
    expect(result.total).toBe(0);
    expect(result.status).toBe('partial');
  });
});

describe('reading a run of results', () => {
  const { habit, entries } = obligationsFor('1/day', '2025-01-10..2025-01-13');
  const resolved = resolveAll(member, [at('2025-01-10T09:00:00Z'), at('2025-01-12T09:00:00Z')]);
  const results = evaluateAll(habit, entries, resolved, d('2025-01-13'));

  it('counts each status', () => {
    expect(countByStatus(results)).toEqual({ met: 2, partial: 0, missed: 1, pending: 1 });
  });

  it('knows which ones are settled', () => {
    expect(results.filter(isSettled)).toHaveLength(3);
  });

  it('rates only the settled ones', () => {
    expect(adherenceRate(results)).toBeCloseTo(2 / 3);
  });

  it('rates an empty run as clean', () => {
    expect(adherenceRate([])).toBe(1);
  });
});
