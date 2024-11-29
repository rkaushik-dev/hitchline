import { describe, expect, it } from 'vitest';
import {
  NO_STREAK,
  bestRun,
  computeStreak,
  describeStreak,
  isAtRisk,
  streakBroken,
} from '../../../../src/domain/adherence/streak.js';
import type { AdherenceStatus, ObligationResult } from '../../../../src/domain/adherence/match.js';
import { shield } from '../../../../src/domain/adherence/shield.js';
import { obligation } from '../../../../src/domain/schedule/obligation.js';
import { crewMemberId, habitId } from '../../../../src/domain/ids.js';
import { formatCivilDate } from '../../../../src/domain/time/civil.js';
import { parseDateRange } from '../../../../src/domain/time/range.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';

const r = parseDateRange;
const ada = crewMemberId('mem_0123456789ABCDEF');

function result(day: string, status: AdherenceStatus): ObligationResult {
  return {
    obligation: obligation({
      habitId: habitId('hab_0123456789ABCDEF'),
      memberId: ada,
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
  memberId: ada,
  range: r('2025-01-13..2025-01-15'),
  reason: 'annual_leave',
  grantedAt: parseInstant('2025-01-04T10:00:00Z'),
});

describe('an empty run', () => {
  it('reads as nothing', () => {
    expect(computeStreak({ results: [], shields: [], memberId: ada })).toEqual(NO_STREAK);
    expect(describeStreak(NO_STREAK)).toBe('no run yet');
  });
});

describe('counting a run', () => {
  it('grows with each met obligation', () => {
    const summary = computeStreak({
      results: [
        result('2025-01-10', 'met'),
        result('2025-01-11', 'met'),
        result('2025-01-12', 'met'),
      ],
      shields: [],
      memberId: ada,
    });
    expect(summary.current).toBe(3);
    expect(summary.longest).toBe(3);
    expect(summary.lastMetOn && formatCivilDate(summary.lastMetOn)).toBe('2025-01-12');
  });

  it('breaks on a missed obligation and starts again', () => {
    const summary = computeStreak({
      results: [
        result('2025-01-10', 'met'),
        result('2025-01-11', 'met'),
        result('2025-01-12', 'missed'),
        result('2025-01-13', 'met'),
      ],
      shields: [],
      memberId: ada,
    });
    expect(summary.current).toBe(1);
    expect(summary.longest).toBe(2);
    expect(summary.brokenOn && formatCivilDate(summary.brokenOn)).toBe('2025-01-12');
  });

  it('breaks on a partial one too', () => {
    const summary = computeStreak({
      results: [result('2025-01-10', 'met'), result('2025-01-11', 'partial')],
      shields: [],
      memberId: ada,
    });
    expect(summary.current).toBe(0);
    expect(streakBroken(summary)).toBe(true);
  });

  it('sorts the results before walking them', () => {
    const summary = computeStreak({
      results: [
        result('2025-01-12', 'met'),
        result('2025-01-10', 'met'),
        result('2025-01-11', 'missed'),
      ],
      shields: [],
      memberId: ada,
    });
    expect(summary.current).toBe(1);
    expect(summary.longest).toBe(1);
  });

  it('ignores anything still pending', () => {
    const summary = computeStreak({
      results: [result('2025-01-10', 'met'), result('2025-01-11', 'pending')],
      shields: [],
      memberId: ada,
    });
    expect(summary.current).toBe(1);
  });
});

describe('shielded days', () => {
  it('steps over a shielded obligation without breaking the run', () => {
    const summary = computeStreak({
      results: [
        result('2025-01-12', 'met'),
        result('2025-01-13', 'missed'),
        result('2025-01-14', 'missed'),
        result('2025-01-16', 'met'),
      ],
      shields: [leave],
      memberId: ada,
    });
    expect(summary.current).toBe(2);
    expect(summary.shielded).toBe(2);
    expect(summary.brokenOn).toBeUndefined();
  });

  it('does not let a shielded day extend the run either', () => {
    const summary = computeStreak({
      results: [result('2025-01-13', 'met'), result('2025-01-14', 'met')],
      shields: [leave],
      memberId: ada,
    });
    expect(summary.current).toBe(0);
    expect(summary.shielded).toBe(2);
  });

  it('ignores a shield granted to somebody else', () => {
    const summary = computeStreak({
      results: [result('2025-01-13', 'missed'), result('2025-01-14', 'met')],
      shields: [leave],
      memberId: crewMemberId('mem_FEDCBA9876543210'),
    });
    expect(summary.current).toBe(1);
    expect(summary.shielded).toBe(0);
  });
});

describe('risk', () => {
  it('is at risk when a run is alive and something is open', () => {
    const summary = computeStreak({
      results: [result('2025-01-10', 'met')],
      shields: [],
      memberId: ada,
    });
    expect(isAtRisk(summary, [result('2025-01-11', 'pending')])).toBe(true);
  });

  it('is not at risk with no run to lose', () => {
    expect(isAtRisk(NO_STREAK, [result('2025-01-11', 'pending')])).toBe(false);
  });

  it('is not at risk when nothing is open', () => {
    const summary = computeStreak({
      results: [result('2025-01-10', 'met')],
      shields: [],
      memberId: ada,
    });
    expect(isAtRisk(summary, [result('2025-01-11', 'met')])).toBe(false);
  });
});

describe('describing a run', () => {
  it('reads the live run', () => {
    const summary = computeStreak({
      results: [result('2025-01-10', 'met'), result('2025-01-11', 'met')],
      shields: [],
      memberId: ada,
    });
    expect(describeStreak(summary)).toBe('2 in a row, best 2');
    expect(bestRun(summary)).toBe(2);
  });

  it('reads a broken run', () => {
    const summary = computeStreak({
      results: [result('2025-01-10', 'met'), result('2025-01-11', 'missed')],
      shields: [],
      memberId: ada,
    });
    expect(describeStreak(summary)).toBe('run broken, best was 1');
  });
});
