import { describe, expect, it } from 'vitest';
import {
  busiestHour,
  byHourOfDay,
  byRole,
  describeTrend,
  trendOf,
} from '../../../../src/domain/adherence/analytics.js';
import type { AdherenceStatus, ObligationResult } from '../../../../src/domain/adherence/match.js';
import type { PeriodSummary } from '../../../../src/domain/adherence/summary.js';
import { resolveAll } from '../../../../src/domain/checkin/resolve.js';
import { checkIn } from '../../../../src/domain/checkin/checkin.js';
import { obligation } from '../../../../src/domain/schedule/obligation.js';
import { checkInId, crewMemberId, habitId } from '../../../../src/domain/ids.js';
import { parseDateRange } from '../../../../src/domain/time/range.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';
import { aMember } from '../../../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const treadmill = habitId('hab_0123456789ABCDEF');
let next = 0;

function at(iso: string) {
  next += 1;
  return checkIn({
    id: checkInId(`chk_${next.toString().padStart(16, '0')}`),
    habitId: treadmill,
    memberId: ada,
    at: parseInstant(iso),
    recordedAt: parseInstant(iso),
    source: 'app',
  });
}

function result(day: string, status: AdherenceStatus): ObligationResult {
  return {
    obligation: obligation({
      habitId: treadmill,
      memberId: ada,
      scope: 'day',
      window: parseDateRange(`${day}..${day}`),
      times: 1,
    }),
    count: status === 'met' ? 1 : 0,
    total: undefined,
    status,
    progress: status === 'met' ? 1 : 0,
  };
}

function summary(rate: number, owed = 10): PeriodSummary {
  return {
    range: parseDateRange('2025-01-06..2025-01-19'),
    owed,
    met: Math.round(rate * owed),
    partial: 0,
    missed: owed - Math.round(rate * owed),
    pending: 0,
    shieldedDays: 0,
    rate,
  };
}

describe('hour of day', () => {
  it('has a bucket for every hour', () => {
    expect(byHourOfDay([])).toHaveLength(24);
    expect(byHourOfDay([]).every((bucket) => bucket.count === 0)).toBe(true);
  });

  it('counts on the clock of the day the check in landed on', () => {
    // 06:00 UTC is 07:00 on the Oslo shift day and 07:00 in Lisbon off shift.
    const resolved = resolveAll(aMember(), [at('2025-01-10T06:00:00Z')]);
    expect(byHourOfDay(resolved)[7]?.count).toBe(1);
  });

  it('reads an off shift check in on the home clock', () => {
    const resolved = resolveAll(aMember(), [at('2025-01-25T06:00:00Z')]);
    expect(byHourOfDay(resolved)[6]?.count).toBe(1);
  });

  it('finds the busiest hour', () => {
    const resolved = resolveAll(aMember(), [
      at('2025-01-10T06:00:00Z'),
      at('2025-01-11T06:10:00Z'),
      at('2025-01-12T17:00:00Z'),
    ]);
    expect(busiestHour(resolved)).toEqual({ hour: 7, count: 2 });
  });

  it('has no busiest hour when nothing was recorded', () => {
    expect(busiestHour([])).toBeUndefined();
  });
});

describe('by role', () => {
  const results = [
    result('2025-01-06', 'met'),
    result('2025-01-07', 'met'),
    result('2025-01-20', 'missed'),
    result('2025-01-21', 'met'),
  ];
  const roleOf = (entry: ObligationResult) =>
    entry.obligation.dueOn.day < 15 ? ('onshift' as const) : ('offshift' as const);

  it('splits the results by the role of the day', () => {
    const split = byRole(results, roleOf);
    expect(split.map((entry) => entry.role)).toEqual(['onshift', 'offshift']);
    expect(split[0]?.rate).toBe(1);
    expect(split[1]?.rate).toBe(0.5);
  });

  it('leaves out a role nothing fell on', () => {
    expect(byRole(results, () => 'onshift')).toHaveLength(1);
  });

  it('reads an empty run as nothing at all', () => {
    expect(byRole([], roleOf)).toEqual([]);
  });
});

describe('trend', () => {
  it('needs three windows before it says anything', () => {
    expect(trendOf([summary(0.5), summary(0.9)])).toBe('unknown');
    expect(trendOf([])).toBe('unknown');
  });

  it('reads a jump as rising', () => {
    expect(trendOf([summary(0.5), summary(0.5), summary(0.9)])).toBe('rising');
  });

  it('reads a drop as falling', () => {
    expect(trendOf([summary(0.9), summary(0.9), summary(0.4)])).toBe('falling');
  });

  it('reads a wobble as steady', () => {
    expect(trendOf([summary(0.7), summary(0.72), summary(0.71)])).toBe('steady');
  });

  it('ignores a window that owed nothing', () => {
    expect(trendOf([summary(0.5), summary(0.5), summary(1, 0), summary(0.9)])).toBe('rising');
  });

  it('takes a tolerance', () => {
    expect(trendOf([summary(0.7), summary(0.7), summary(0.74)], 0.01)).toBe('rising');
  });

  it('says what it means in words', () => {
    expect(describeTrend('rising')).toContain('better');
    expect(describeTrend('unknown')).toContain('not enough');
  });
});
