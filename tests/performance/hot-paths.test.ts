import { describe, expect, it } from 'vitest';
import { describeTiming, isSlowerThan, measure } from '../../src/infra/measure.js';
import { expandHabit } from '../../src/domain/schedule/expand.js';
import { evaluateAll } from '../../src/domain/adherence/match.js';
import { computeStreak } from '../../src/domain/adherence/streak.js';
import { roster } from '../../src/domain/rotation/roster.js';
import { resolveAll } from '../../src/domain/checkin/resolve.js';
import { checkIn } from '../../src/domain/checkin/checkin.js';
import { checkInId, crewMemberId, habitId } from '../../src/domain/ids.js';
import { addDays, parseCivilDate } from '../../src/domain/time/civil.js';
import { dateRange, parseDateRange } from '../../src/domain/time/range.js';
import { zonedInstant } from '../../src/domain/time/zone.js';
import { parseClockTime } from '../../src/domain/time/clocktime.js';
import { ValidationError } from '../../src/domain/errors.js';
import { aHabit, aMember } from '../support/fixtures.js';

const member = aMember();
const target = roster(member);
const year = dateRange(parseCivilDate('2025-01-01'), parseCivilDate('2025-12-31'));

function aYearOfCheckIns(): ReturnType<typeof resolveAll> {
  const entries = [];
  for (let day = 0; day < 365; day += 1) {
    const date = addDays(parseCivilDate('2025-01-01'), day);
    const at = zonedInstant('Europe/Oslo', date, parseClockTime('07:30'));
    entries.push(
      checkIn({
        id: checkInId(`chk_${(day + 1).toString().padStart(16, '0')}`),
        habitId: habitId('hab_0123456789ABCDEF'),
        memberId: crewMemberId('mem_0123456789ABCDEF'),
        at,
        recordedAt: at,
        source: 'app',
      }),
    );
  }
  return resolveAll(member, entries);
}

describe('the stopwatch', () => {
  it('reads a median and a worst case', () => {
    let tick = 0;
    const readings = [0, 5, 0, 1, 0, 9, 0, 3, 0, 2, 0, 4];
    const timing = measure('fake', () => undefined, {
      runs: 5,
      warmup: 1,
      now: () => readings[tick++] ?? 0,
    });
    expect(timing.runs).toBe(5);
    expect(timing.medianMs).toBeGreaterThan(0);
    expect(timing.worstMs).toBeGreaterThanOrEqual(timing.medianMs);
  });

  it('refuses to run nothing', () => {
    expect(() => measure('none', () => undefined, { runs: 0 })).toThrow(ValidationError);
  });

  it('reads back in one line', () => {
    const timing = { name: 'expand', runs: 5, medianMs: 12.5, worstMs: 20, totalMs: 70 };
    expect(describeTiming(timing)).toBe('expand: 12.5ms median over 5 runs, worst 20ms');
  });

  it('compares against a budget', () => {
    const timing = { name: 'expand', runs: 5, medianMs: 12.5, worstMs: 20, totalMs: 70 };
    expect(isSlowerThan(timing, 10)).toBe(true);
    expect(isSlowerThan(timing, 50)).toBe(false);
  });
});

describe('the paths that grow with a crew', () => {
  it('expands a year of daily obligations well inside the budget', () => {
    const habit = aHabit({ cadence: '1/day', startsOn: '2025-01-01' });
    const timing = measure('expand a year', () => {
      expandHabit({ roster: target, habit, window: year });
    });
    expect(isSlowerThan(timing, 250)).toBe(false);
  });

  it('expands a year of shift day obligations well inside the budget', () => {
    const habit = aHabit({ cadence: '1/onshift-day', startsOn: '2025-01-01' });
    const timing = measure('expand a year of shift days', () => {
      expandHabit({ roster: target, habit, window: year });
    });
    expect(isSlowerThan(timing, 250)).toBe(false);
  });

  it('scores a year of obligations against a year of check ins', () => {
    const habit = aHabit({ cadence: '1/day', startsOn: '2025-01-01' });
    const obligations = expandHabit({ roster: target, habit, window: year });
    const resolved = aYearOfCheckIns();
    const timing = measure('score a year', () => {
      evaluateAll(habit, obligations, resolved, parseCivilDate('2026-01-01'));
    });
    expect(obligations).toHaveLength(365);
    expect(isSlowerThan(timing, 400)).toBe(false);
  });

  it('walks a year of results for a streak', () => {
    const habit = aHabit({ cadence: '1/day', startsOn: '2025-01-01' });
    const obligations = expandHabit({ roster: target, habit, window: year });
    const results = evaluateAll(
      habit,
      obligations,
      aYearOfCheckIns(),
      parseCivilDate('2026-01-01'),
    );
    const timing = measure('streak over a year', () => {
      computeStreak({ results, shields: [], memberId: member.id });
    });
    expect(isSlowerThan(timing, 100)).toBe(false);
  });

  it('expands a hitch cadence over a year without walking every day', () => {
    const habit = aHabit({ cadence: '3/hitch', startsOn: '2025-01-01' });
    const timing = measure('expand hitches', () => {
      expandHabit({ roster: target, habit, window: parseDateRange('2025-01-01..2025-12-31') });
    });
    expect(isSlowerThan(timing, 100)).toBe(false);
  });
});
