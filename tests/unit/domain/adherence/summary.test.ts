import { describe, expect, it } from 'vitest';
import {
  describeSummary,
  isClean,
  settledCount,
  summarise,
  summariseByHabit,
  summariseByHitch,
} from '../../../../src/domain/adherence/summary.js';
import type { AdherenceStatus, ObligationResult } from '../../../../src/domain/adherence/match.js';
import { shield } from '../../../../src/domain/adherence/shield.js';
import { obligation } from '../../../../src/domain/schedule/obligation.js';
import { roster } from '../../../../src/domain/rotation/roster.js';
import { crewMemberId, habitId } from '../../../../src/domain/ids.js';
import { formatDateRange, parseDateRange } from '../../../../src/domain/time/range.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';
import { aMember } from '../../../support/fixtures.js';

const r = parseDateRange;
const member = aMember();
const plain = roster(member);
const treadmill = habitId('hab_0123456789ABCDEF');
const stretches = habitId('hab_FEDCBA9876543210');

function result(day: string, status: AdherenceStatus, habit = treadmill): ObligationResult {
  return {
    obligation: obligation({
      habitId: habit,
      memberId: member.id,
      scope: 'day',
      window: r(`${day}..${day}`),
      times: 1,
    }),
    count: status === 'met' ? 1 : 0,
    total: undefined,
    status,
    progress: status === 'met' ? 1 : 0,
  };
}

const leave = shield({
  memberId: crewMemberId('mem_0123456789ABCDEF'),
  range: r('2025-01-13..2025-01-15'),
  reason: 'annual_leave',
  grantedAt: parseInstant('2025-01-04T10:00:00Z'),
});

const results = [
  result('2025-01-10', 'met'),
  result('2025-01-11', 'missed'),
  result('2025-01-12', 'partial'),
  result('2025-01-13', 'met'),
  result('2025-02-05', 'met'),
  result('2025-02-06', 'pending'),
];

describe('summarising a window', () => {
  const summary = summarise({
    roster: plain,
    results,
    shields: [leave],
    range: r('2025-01-01..2025-01-31'),
  });

  it('counts what was owed and how it went', () => {
    expect(summary.owed).toBe(4);
    expect(summary.met).toBe(2);
    expect(summary.missed).toBe(1);
    expect(summary.partial).toBe(1);
    expect(summary.pending).toBe(0);
  });

  it('rates the settled obligations', () => {
    expect(summary.rate).toBe(0.5);
  });

  it('counts the shielded days in the window', () => {
    expect(summary.shieldedDays).toBe(3);
  });

  it('leaves out obligations from outside the window', () => {
    expect(summary.owed).toBeLessThan(results.length);
  });

  it('reads back as a line', () => {
    expect(describeSummary(summary)).toBe('2025-01-01..2025-01-31 2 of 4 at 50%');
  });
});

describe('a clean window', () => {
  it('has nothing missed or partial', () => {
    const summary = summarise({
      roster: plain,
      results: [result('2025-01-10', 'met'), result('2025-01-11', 'pending')],
      shields: [],
      range: r('2025-01-01..2025-01-31'),
    });
    expect(isClean(summary)).toBe(true);
    expect(summary.pending).toBe(1);
  });

  it('is not clean once something is missed', () => {
    const summary = summarise({
      roster: plain,
      results: [result('2025-01-11', 'missed')],
      shields: [],
      range: r('2025-01-01..2025-01-31'),
    });
    expect(isClean(summary)).toBe(false);
  });
});

describe('summarising by habit', () => {
  it('splits the window by habit and sorts by id', () => {
    const byHabit = summariseByHabit({
      roster: plain,
      results: [
        result('2025-01-10', 'met'),
        result('2025-01-11', 'missed'),
        result('2025-01-10', 'met', stretches),
      ],
      shields: [],
      range: r('2025-01-01..2025-01-31'),
    });
    expect(byHabit.map((entry) => entry.habitId)).toEqual([treadmill, stretches].sort());
    expect(byHabit.find((entry) => entry.habitId === stretches)?.rate).toBe(1);
    expect(byHabit.find((entry) => entry.habitId === treadmill)?.rate).toBe(0.5);
  });

  it('returns nothing for a window with no obligations', () => {
    const byHabit = summariseByHabit({
      roster: plain,
      results,
      shields: [],
      range: r('2025-06-01..2025-06-30'),
    });
    expect(byHabit).toEqual([]);
  });
});

describe('summarising by hitch', () => {
  const byHitch = summariseByHitch({
    roster: plain,
    results,
    shields: [],
    range: r('2025-01-10..2025-02-06'),
  });

  it('gives one summary per turn of the rotation', () => {
    expect(byHitch.map((entry) => formatDateRange(entry.range))).toEqual([
      '2025-01-06..2025-02-02',
      '2025-02-03..2025-03-02',
    ]);
  });

  it('puts each obligation in the hitch it fell due in', () => {
    expect(byHitch[0]?.owed).toBe(4);
    expect(byHitch[1]?.owed).toBe(2);
  });

  it('covers the whole hitch even when the window is narrower', () => {
    const narrow = summariseByHitch({
      roster: plain,
      results,
      shields: [],
      range: r('2025-01-20..2025-01-21'),
    });
    expect(narrow).toHaveLength(1);
    expect(narrow[0]?.owed).toBe(4);
  });
});

describe('settledCount', () => {
  it('leaves out the pending ones', () => {
    expect(settledCount(results)).toBe(5);
  });
});
