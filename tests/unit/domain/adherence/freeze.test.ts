import { describe, expect, it } from 'vitest';
import {
  DEFAULT_FREEZE,
  computeFrozenStreak,
  describeFreezes,
  freezeBudget,
  lastMetLabel,
} from '../../../../src/domain/adherence/freeze.js';
import type { AdherenceStatus, ObligationResult } from '../../../../src/domain/adherence/match.js';
import { shield } from '../../../../src/domain/adherence/shield.js';
import { obligation } from '../../../../src/domain/schedule/obligation.js';
import { crewMemberId, habitId } from '../../../../src/domain/ids.js';
import { addDays, formatCivilDate, parseCivilDate } from '../../../../src/domain/time/civil.js';
import { parseDateRange } from '../../../../src/domain/time/range.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';
import { ValidationError } from '../../../../src/domain/errors.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const treadmill = habitId('hab_0123456789ABCDEF');
const start = parseCivilDate('2025-06-02');

function run(statuses: readonly AdherenceStatus[]): ObligationResult[] {
  return statuses.map((status, index) => ({
    obligation: obligation({
      habitId: treadmill,
      memberId: ada,
      scope: 'day',
      window: parseDateRange(
        `${formatCivilDate(addDays(start, index))}..${formatCivilDate(addDays(start, index))}`,
      ),
      times: 1,
    }),
    count: status === 'met' ? 1 : 0,
    total: undefined,
    status,
    progress: status === 'met' ? 1 : 0,
  }));
}

describe('the budget', () => {
  it('allows one miss a month by default', () => {
    expect(DEFAULT_FREEZE).toEqual({ allowance: 1, windowDays: 28 });
    expect(freezeBudget()).toEqual(DEFAULT_FREEZE);
  });

  it('refuses a nonsense allowance or window', () => {
    expect(() => freezeBudget({ allowance: -1 })).toThrow(ValidationError);
    expect(() => freezeBudget({ windowDays: 0 })).toThrow(/from 1 to 365/);
    expect(() => freezeBudget({ allowance: 11 })).toThrow(/from 0 to 10/);
  });
});

describe('carrying a run over a miss', () => {
  it('absorbs one miss and keeps counting', () => {
    const summary = computeFrozenStreak({
      results: run(['met', 'met', 'missed', 'met']),
      shields: [],
      memberId: ada,
    });
    expect(summary.current).toBe(3);
    expect(summary.frozen).toBe(1);
    expect(summary.freezesLeft).toBe(0);
  });

  it('breaks on the second miss inside the window', () => {
    const summary = computeFrozenStreak({
      results: run(['met', 'missed', 'met', 'missed', 'met']),
      shields: [],
      memberId: ada,
    });
    expect(summary.current).toBe(1);
    expect(summary.frozen).toBe(1);
    expect(summary.brokenOn && formatCivilDate(summary.brokenOn)).toBe('2025-06-05');
  });

  it('gives the freeze back once the window has passed', () => {
    const statuses: AdherenceStatus[] = ['met', 'missed'];
    for (let day = 0; day < 30; day += 1) statuses.push('met');
    statuses.push('missed', 'met');
    const summary = computeFrozenStreak({ results: run(statuses), shields: [], memberId: ada });
    expect(summary.frozen).toBe(2);
    expect(summary.current).toBeGreaterThan(30);
  });

  it('will not freeze the first thing in a run', () => {
    const summary = computeFrozenStreak({
      results: run(['missed', 'met', 'met']),
      shields: [],
      memberId: ada,
    });
    expect(summary.frozen).toBe(0);
    expect(summary.current).toBe(2);
  });

  it('takes a wider allowance', () => {
    const summary = computeFrozenStreak({
      results: run(['met', 'missed', 'missed', 'met']),
      shields: [],
      memberId: ada,
      budget: freezeBudget({ allowance: 2 }),
    });
    expect(summary.current).toBe(2);
    expect(summary.frozen).toBe(2);
  });

  it('breaks every time with no allowance at all', () => {
    const summary = computeFrozenStreak({
      results: run(['met', 'missed', 'met']),
      shields: [],
      memberId: ada,
      budget: freezeBudget({ allowance: 0 }),
    });
    expect(summary.current).toBe(1);
    expect(summary.frozen).toBe(0);
  });
});

describe('shields and freezes together', () => {
  it('spends nothing on a day that was shielded', () => {
    const leave = shield({
      memberId: ada,
      range: parseDateRange('2025-06-04..2025-06-04'),
      reason: 'annual_leave',
      grantedAt: parseInstant('2025-06-01T09:00:00Z'),
    });
    const summary = computeFrozenStreak({
      results: run(['met', 'met', 'missed', 'met']),
      shields: [leave],
      memberId: ada,
    });
    expect(summary.shielded).toBe(1);
    expect(summary.frozen).toBe(0);
    expect(summary.current).toBe(3);
    expect(summary.freezesLeft).toBe(1);
  });
});

describe('reading it back', () => {
  it('says what is in hand', () => {
    const clean = computeFrozenStreak({ results: run(['met']), shields: [], memberId: ada });
    expect(describeFreezes(clean)).toBe('1 freezes in hand');
    const used = computeFrozenStreak({
      results: run(['met', 'missed', 'met']),
      shields: [],
      memberId: ada,
    });
    expect(describeFreezes(used)).toBe('one day carried, 0 freezes in hand');
  });

  it('labels the last day something was met', () => {
    const summary = computeFrozenStreak({ results: run(['met']), shields: [], memberId: ada });
    expect(lastMetLabel(summary)).toBe('2025-06-02');
    expect(lastMetLabel({ ...summary, lastMetOn: undefined })).toBe('never');
  });
});
