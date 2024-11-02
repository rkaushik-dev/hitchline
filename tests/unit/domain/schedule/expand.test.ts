import { describe, expect, it } from 'vitest';
import { expandAll, expandHabit, habitWindow } from '../../../../src/domain/schedule/expand.js';
import { obligationKey } from '../../../../src/domain/schedule/obligation.js';
import { roster } from '../../../../src/domain/rotation/roster.js';
import { rosterOverride } from '../../../../src/domain/rotation/override.js';
import { formatDateRange, parseDateRange } from '../../../../src/domain/time/range.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';
import { UnsupportedError } from '../../../../src/domain/errors.js';
import { aHabit, aMember } from '../../../support/fixtures.js';

const r = parseDateRange;
const member = aMember();
const plain = roster(member);

describe('a calendar cadence', () => {
  it('asks once on every day of the window', () => {
    const entries = expandHabit({
      roster: plain,
      habit: aHabit({ cadence: '1/day' }),
      window: r('2025-01-06..2025-01-09'),
    });
    expect(entries).toHaveLength(4);
    expect(entries.map(obligationKey)).toEqual([
      'hab_0123456789ABCDEF:day:2025-01-06',
      'hab_0123456789ABCDEF:day:2025-01-07',
      'hab_0123456789ABCDEF:day:2025-01-08',
      'hab_0123456789ABCDEF:day:2025-01-09',
    ]);
  });

  it('carries the count through', () => {
    const entries = expandHabit({
      roster: plain,
      habit: aHabit({ cadence: '3/day' }),
      window: r('2025-01-06..2025-01-07'),
    });
    expect(entries.every((entry) => entry.times === 3)).toBe(true);
  });

  it('stops at the habit start and end dates', () => {
    const entries = expandHabit({
      roster: plain,
      habit: aHabit({ cadence: '1/day', startsOn: '2025-01-08', endsOn: '2025-01-09' }),
      window: r('2025-01-06..2025-01-12'),
    });
    expect(entries.map((entry) => formatDateRange(entry.window))).toEqual([
      '2025-01-08..2025-01-08',
      '2025-01-09..2025-01-09',
    ]);
  });
});

describe('a role day cadence', () => {
  it('asks only on shift days', () => {
    const entries = expandHabit({
      roster: plain,
      habit: aHabit({ cadence: '1/onshift-day' }),
      window: r('2025-01-17..2025-01-22'),
    });
    expect(entries.map((entry) => formatDateRange(entry.window))).toEqual([
      '2025-01-17..2025-01-17',
      '2025-01-18..2025-01-18',
      '2025-01-19..2025-01-19',
    ]);
  });

  it('asks for nothing over a fortnight at home', () => {
    const entries = expandHabit({
      roster: plain,
      habit: aHabit({ cadence: '1/onshift-day' }),
      window: r('2025-01-20..2025-02-02'),
    });
    expect(entries).toEqual([]);
  });

  it('follows an override rather than the pattern', () => {
    const held = roster(member, [
      rosterOverride({
        memberId: member.id,
        range: r('2025-01-20..2025-01-22'),
        role: 'onshift',
        reason: 'held_over',
        enteredAt: parseInstant('2025-01-18T10:00:00Z'),
      }),
    ]);
    const entries = expandHabit({
      roster: held,
      habit: aHabit({ cadence: '1/onshift-day' }),
      window: r('2025-01-20..2025-01-24'),
    });
    expect(entries).toHaveLength(3);
  });

  it('reads several roles at once', () => {
    const withTravel = aMember({ pattern: '1travel 13on 1travel 13off' });
    const entries = expandHabit({
      roster: roster(withTravel),
      habit: aHabit({ cadence: '1/onshift+travel-day' }),
      window: r('2025-01-06..2025-01-08'),
    });
    expect(entries).toHaveLength(3);
  });
});

describe('grace days', () => {
  it('widens the window a check in may land in', () => {
    const entries = expandHabit({
      roster: plain,
      habit: aHabit({ cadence: '1/day', graceDays: 2 }),
      window: r('2025-01-06..2025-01-07'),
    });
    expect(entries.map((entry) => formatDateRange(entry.window))).toEqual([
      '2025-01-06..2025-01-08',
      '2025-01-07..2025-01-09',
    ]);
  });

  it('still reports the day it was owed on', () => {
    const [first] = expandHabit({
      roster: plain,
      habit: aHabit({ cadence: '1/day', graceDays: 2 }),
      window: r('2025-01-06..2025-01-06'),
    });
    expect(first && obligationKey(first)).toBe('hab_0123456789ABCDEF:day:2025-01-08');
  });
});

describe('cadences that are not ready yet', () => {
  it('says so rather than guessing', () => {
    expect(() =>
      expandHabit({
        roster: plain,
        habit: aHabit({ cadence: '3/hitch' }),
        window: r('2025-01-06..2025-02-02'),
      }),
    ).toThrow(UnsupportedError);
  });
});

describe('expandAll', () => {
  it('merges several habits into one sorted list', () => {
    const entries = expandAll(
      plain,
      [
        aHabit({ cadence: '1/day' }),
        aHabit({ id: 'hab_FEDCBA9876543210', cadence: '1/onshift-day' }),
      ],
      r('2025-01-19..2025-01-20'),
    );
    expect(entries.map(obligationKey)).toEqual([
      'hab_0123456789ABCDEF:day:2025-01-19',
      'hab_FEDCBA9876543210:day:2025-01-19',
      'hab_0123456789ABCDEF:day:2025-01-20',
    ]);
  });
});

describe('habitWindow', () => {
  it('clips to the life of the habit', () => {
    const window = habitWindow(
      aHabit({ startsOn: '2025-01-08', endsOn: '2025-01-20' }),
      r('2025-01-01..2025-01-31'),
    );
    expect(window && formatDateRange(window)).toBe('2025-01-08..2025-01-20');
  });

  it('runs to the end of the window when the habit has no end', () => {
    const window = habitWindow(aHabit({ startsOn: '2025-01-08' }), r('2025-01-01..2025-01-31'));
    expect(window && formatDateRange(window)).toBe('2025-01-08..2025-01-31');
  });

  it('returns nothing when the habit is over before the window opens', () => {
    const entry = aHabit({ startsOn: '2024-01-01', endsOn: '2024-06-30' });
    expect(habitWindow(entry, r('2025-01-01..2025-01-31'))).toBeUndefined();
  });
});
