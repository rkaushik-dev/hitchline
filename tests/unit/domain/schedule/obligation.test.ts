import { describe, expect, it } from 'vitest';
import {
  acceptsCheckInOn,
  describeObligation,
  isOverdueOn,
  obligation,
  obligationKey,
  obligationSpan,
  obligationsForHabit,
  sortObligations,
  totalTimes,
  type ObligationInput,
} from '../../../../src/domain/schedule/obligation.js';
import { crewMemberId, habitId } from '../../../../src/domain/ids.js';
import { formatCivilDate, parseCivilDate } from '../../../../src/domain/time/civil.js';
import { parseDateRange } from '../../../../src/domain/time/range.js';

const d = parseCivilDate;
const r = parseDateRange;
const treadmill = habitId('hab_0123456789ABCDEF');
const stretches = habitId('hab_FEDCBA9876543210');
const ada = crewMemberId('mem_0123456789ABCDEF');

function an(overrides: Partial<ObligationInput> = {}) {
  return obligation({
    habitId: treadmill,
    memberId: ada,
    scope: 'day',
    window: r('2025-01-10..2025-01-10'),
    times: 1,
    ...overrides,
  });
}

describe('creating an obligation', () => {
  it('takes the due date from the end of the window', () => {
    const entry = an({ scope: 'hitch', window: r('2025-01-06..2025-02-02'), times: 3 });
    expect(formatCivilDate(entry.dueOn)).toBe('2025-02-02');
  });

  it('refuses a count below one', () => {
    expect(() => an({ times: 0 })).toThrow(/above zero/);
    expect(() => an({ times: 1.5 })).toThrow(/whole number/);
  });
});

describe('identity', () => {
  it('keys on the habit, the scope and the due day', () => {
    expect(obligationKey(an())).toBe('hab_0123456789ABCDEF:day:2025-01-10');
  });

  it('separates two scopes that fall due on the same day', () => {
    const daily = an({ window: r('2025-02-02..2025-02-02') });
    const perHitch = an({ scope: 'hitch', window: r('2025-01-06..2025-02-02'), times: 3 });
    expect(obligationKey(daily)).not.toBe(obligationKey(perHitch));
  });
});

describe('the window', () => {
  const perHitch = an({ scope: 'hitch', window: r('2025-01-06..2025-02-02'), times: 3 });

  it('accepts a check in anywhere inside it', () => {
    expect(acceptsCheckInOn(perHitch, d('2025-01-06'))).toBe(true);
    expect(acceptsCheckInOn(perHitch, d('2025-01-20'))).toBe(true);
    expect(acceptsCheckInOn(perHitch, d('2025-02-02'))).toBe(true);
  });

  it('turns one away from outside it', () => {
    expect(acceptsCheckInOn(perHitch, d('2025-01-05'))).toBe(false);
    expect(acceptsCheckInOn(perHitch, d('2025-02-03'))).toBe(false);
  });

  it('measures how much room there was', () => {
    expect(obligationSpan(perHitch)).toBe(28);
    expect(obligationSpan(an())).toBe(1);
  });

  it('is overdue once the window has passed', () => {
    expect(isOverdueOn(perHitch, d('2025-02-02'))).toBe(false);
    expect(isOverdueOn(perHitch, d('2025-02-03'))).toBe(true);
  });
});

describe('collections', () => {
  const entries = [
    an({ window: r('2025-01-12..2025-01-12') }),
    an({ habitId: stretches, window: r('2025-01-10..2025-01-10') }),
    an({ window: r('2025-01-10..2025-01-10') }),
  ];

  it('sorts by window then by habit', () => {
    expect(sortObligations(entries).map(obligationKey)).toEqual([
      'hab_0123456789ABCDEF:day:2025-01-10',
      'hab_FEDCBA9876543210:day:2025-01-10',
      'hab_0123456789ABCDEF:day:2025-01-12',
    ]);
  });

  it('filters to one habit', () => {
    expect(obligationsForHabit(entries, stretches)).toHaveLength(1);
  });

  it('totals what is owed', () => {
    expect(totalTimes(entries)).toBe(3);
    expect(totalTimes([an({ times: 2 }), an({ times: 3 })])).toBe(5);
  });
});

describe('describing an obligation', () => {
  it('reads once for a single time', () => {
    expect(describeObligation(an())).toBe('once per day over 2025-01-10..2025-01-10');
  });

  it('counts up for more', () => {
    const perHitch = an({ scope: 'hitch', window: r('2025-01-06..2025-02-02'), times: 3 });
    expect(describeObligation(perHitch)).toBe('3 times per hitch over 2025-01-06..2025-02-02');
  });
});
