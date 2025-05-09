import { describe, expect, it } from 'vitest';
import { forEachSeed, source } from '../support/source.js';
import { computeStreak } from '../../src/domain/adherence/streak.js';
import type { AdherenceStatus, ObligationResult } from '../../src/domain/adherence/match.js';
import { shield } from '../../src/domain/adherence/shield.js';
import { obligation } from '../../src/domain/schedule/obligation.js';
import { expandHabit } from '../../src/domain/schedule/expand.js';
import { roster } from '../../src/domain/rotation/roster.js';
import { crewMemberId, habitId } from '../../src/domain/ids.js';
import { addDays, formatCivilDate, parseCivilDate } from '../../src/domain/time/civil.js';
import { dateRange, parseDateRange, rangeLength } from '../../src/domain/time/range.js';
import { parseInstant } from '../../src/domain/time/zone.js';
import { aHabit, aMember } from '../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const treadmill = habitId('hab_0123456789ABCDEF');
const STATUSES: AdherenceStatus[] = ['met', 'partial', 'missed'];

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

describe('the generator itself', () => {
  it('is the same for the same seed', () => {
    expect(source(5).int(0, 100)).toBe(source(5).int(0, 100));
  });

  it('stays inside the range it was given', () => {
    const numbers = source(11);
    for (let index = 0; index < 200; index += 1) {
      const value = numbers.int(3, 7);
      expect(value).toBeGreaterThanOrEqual(3);
      expect(value).toBeLessThanOrEqual(7);
    }
  });

  it('names the seed when a body fails', () => {
    expect(() =>
      forEachSeed(3, (_source, seed) => {
        if (seed === 2) throw new Error('deliberate');
      }),
    ).toThrow(/seed 2: deliberate/);
  });
});

describe('streak invariants', () => {
  it('never counts more than the run of met results at the end', () => {
    forEachSeed(60, (numbers) => {
      const statuses = Array.from({ length: numbers.int(1, 30) }, () => numbers.pick(STATUSES));
      const results = statuses.map((status, index) =>
        result(formatCivilDate(addDays(parseCivilDate('2025-01-06'), index)), status),
      );
      const summary = computeStreak({ results, shields: [], memberId: ada });

      let tail = 0;
      for (let index = statuses.length - 1; index >= 0; index -= 1) {
        if (statuses[index] !== 'met') break;
        tail += 1;
      }
      expect(summary.current).toBe(tail);
    });
  });

  it('never reports a longest run below the current one', () => {
    forEachSeed(60, (numbers) => {
      const results = Array.from({ length: numbers.int(1, 20) }, (_, index) =>
        result(
          formatCivilDate(addDays(parseCivilDate('2025-01-06'), index)),
          numbers.pick(STATUSES),
        ),
      );
      const summary = computeStreak({ results, shields: [], memberId: ada });
      expect(summary.longest).toBeGreaterThanOrEqual(summary.current);
    });
  });

  it('shielding every day leaves no run at all', () => {
    forEachSeed(30, (numbers) => {
      const length = numbers.int(1, 20);
      const from = parseCivilDate('2025-01-06');
      const results = Array.from({ length }, (_, index) =>
        result(formatCivilDate(addDays(from, index)), numbers.pick(STATUSES)),
      );
      const all = shield({
        memberId: ada,
        range: dateRange(from, addDays(from, length - 1)),
        reason: 'annual_leave',
        grantedAt: parseInstant('2025-01-01T09:00:00Z'),
      });
      const summary = computeStreak({ results, shields: [all], memberId: ada });
      expect(summary.current).toBe(0);
      expect(summary.shielded).toBe(length);
    });
  });
});

describe('expansion invariants', () => {
  const PATTERNS = ['14/14', '2-2-3', '7on 7off', '1travel 13on 1travel 13off'];

  it('never asks for anything outside the window it was given', () => {
    forEachSeed(40, (numbers) => {
      const member = aMember({ pattern: numbers.pick(PATTERNS), anchor: '2025-01-06' });
      const start = addDays(parseCivilDate('2025-01-01'), numbers.int(0, 120));
      const window = dateRange(start, addDays(start, numbers.int(0, 60)));
      const habit = aHabit({
        cadence: numbers.pick(['1/day', '1/onshift-day', '2/hitch', '3/7d']),
        startsOn: '2025-01-01',
      });
      for (const entry of expandHabit({ roster: roster(member), habit, window })) {
        expect(entry.window.to.year).toBeGreaterThanOrEqual(2024);
        expect(rangeLength(entry.window)).toBeGreaterThan(0);
        expect(entry.times).toBeGreaterThan(0);
      }
    });
  });

  it('gives every obligation a distinct key', () => {
    forEachSeed(40, (numbers) => {
      const member = aMember({ pattern: numbers.pick(PATTERNS), anchor: '2025-01-06' });
      const start = addDays(parseCivilDate('2025-01-01'), numbers.int(0, 90));
      const window = dateRange(start, addDays(start, numbers.int(1, 90)));
      const habit = aHabit({
        cadence: numbers.pick(['1/day', '1/onshift-day', '2/hitch', '1/onshift-block', '3/7d']),
        startsOn: '2025-01-01',
      });
      const entries = expandHabit({ roster: roster(member), habit, window });
      const keys = new Set(
        entries.map((entry) => `${entry.scope}:${formatCivilDate(entry.dueOn)}`),
      );
      expect(keys.size).toBe(entries.length);
    });
  });

  it('asks for nothing at all before the habit starts', () => {
    forEachSeed(30, (numbers) => {
      const member = aMember({ pattern: numbers.pick(PATTERNS), anchor: '2025-01-06' });
      const habit = aHabit({
        cadence: numbers.pick(['1/day', '1/onshift-day']),
        startsOn: '2025-06-01',
      });
      const entries = expandHabit({
        roster: roster(member),
        habit,
        window: parseDateRange('2025-01-01..2025-05-31'),
      });
      expect(entries).toEqual([]);
    });
  });
});
