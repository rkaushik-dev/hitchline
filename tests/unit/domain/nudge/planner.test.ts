import { describe, expect, it } from 'vitest';
import {
  capPerDay,
  describeNudge,
  nudgesForHabit,
  planNudges,
  type PlannedNudge,
} from '../../../../src/domain/nudge/planner.js';
import { nudgePolicy } from '../../../../src/domain/nudge/policy.js';
import type { AdherenceStatus, ObligationResult } from '../../../../src/domain/adherence/match.js';
import { obligation } from '../../../../src/domain/schedule/obligation.js';
import { roster } from '../../../../src/domain/rotation/roster.js';
import { habitId } from '../../../../src/domain/ids.js';
import { parseCivilDate } from '../../../../src/domain/time/civil.js';
import { parseDateRange } from '../../../../src/domain/time/range.js';
import { formatInstant, parseInstant } from '../../../../src/domain/time/zone.js';
import { aMember } from '../../../support/fixtures.js';

const r = parseDateRange;
const member = aMember();
const plain = roster(member);
const treadmill = habitId('hab_0123456789ABCDEF');
const stretches = habitId('hab_FEDCBA9876543210');

function pending(window: string, count = 0, times = 1, habit = treadmill): ObligationResult {
  return {
    obligation: obligation({
      habitId: habit,
      memberId: member.id,
      scope: 'day',
      window: r(window),
      times,
    }),
    count,
    total: undefined,
    status: 'pending' as AdherenceStatus,
    progress: 0,
  };
}

const asOf = parseInstant('2025-01-10T06:00:00Z');
const policy = nudgePolicy({ leadMinutes: 240, quietHours: '22:00-06:00' });

describe('planning a nudge', () => {
  it('lands four hours before the end of the local day', () => {
    const [nudge] = planNudges({
      roster: plain,
      policy,
      results: [pending('2025-01-10..2025-01-10')],
      asOf,
    });
    expect(nudge && formatInstant(nudge.at)).toBe('2025-01-10T19:00:00.000Z');
  });

  it('uses the home clock on an off shift day', () => {
    const [nudge] = planNudges({
      roster: plain,
      policy,
      results: [pending('2025-01-25..2025-01-25')],
      asOf,
    });
    expect(nudge && formatInstant(nudge.at)).toBe('2025-01-25T20:00:00.000Z');
  });

  it('says nothing has been done yet', () => {
    const [nudge] = planNudges({
      roster: plain,
      policy,
      results: [pending('2025-01-10..2025-01-10')],
      asOf,
    });
    expect(nudge?.reason).toBe('nothing_yet');
  });

  it('says part way when something is already in', () => {
    const [nudge] = planNudges({
      roster: plain,
      policy,
      results: [pending('2025-01-10..2025-01-10', 1, 2)],
      asOf,
    });
    expect(nudge?.reason).toBe('part_way');
  });

  it('says last chance when the window was longer than a day', () => {
    const [nudge] = planNudges({
      roster: plain,
      policy,
      results: [pending('2025-01-06..2025-01-19')],
      asOf,
    });
    expect(nudge?.reason).toBe('last_chance');
  });
});

describe('what is left out', () => {
  it('leaves out anything already settled', () => {
    const met: ObligationResult = { ...pending('2025-01-10..2025-01-10'), status: 'met', count: 1 };
    expect(planNudges({ roster: plain, policy, results: [met], asOf })).toEqual([]);
  });

  it('leaves out an obligation already done enough times', () => {
    const done = pending('2025-01-10..2025-01-10', 2, 2);
    expect(planNudges({ roster: plain, policy, results: [done], asOf })).toEqual([]);
  });

  it('leaves out a nudge whose moment has passed', () => {
    const late = parseInstant('2025-01-10T20:00:00Z');
    const results = [pending('2025-01-10..2025-01-10')];
    expect(planNudges({ roster: plain, policy, results, asOf: late })).toEqual([]);
  });

  it('leaves out a day the policy does not nudge on', () => {
    const flying = roster(aMember({ pattern: '1travel 13on 1travel 13off' }));
    const results = [pending('2025-01-06..2025-01-06')];
    expect(planNudges({ roster: flying, policy, results, asOf })).toEqual([]);
  });

  it('plans nothing at all for a silent policy', () => {
    const silent = nudgePolicy({ maxPerDay: 0 });
    const results = [pending('2025-01-10..2025-01-10')];
    expect(planNudges({ roster: plain, policy: silent, results, asOf })).toEqual([]);
  });

  it('drops a nudge that quiet hours would push past the window', () => {
    const nocturnal = nudgePolicy({ leadMinutes: 60, quietHours: '22:00-06:00' });
    const results = [pending('2025-01-10..2025-01-10')];
    expect(planNudges({ roster: plain, policy: nocturnal, results, asOf })).toEqual([]);
  });
});

describe('capping', () => {
  const day = parseCivilDate('2025-01-10');

  function planned(minutes: number, habit = treadmill): PlannedNudge {
    return {
      habitId: habit,
      key: `${habit}:day:2025-01-10`,
      at: parseInstant(`2025-01-10T${String(minutes).padStart(2, '0')}:00:00Z`),
      onDate: day,
      reason: 'nothing_yet',
    };
  }

  it('keeps the earliest few', () => {
    const capped = capPerDay(
      [planned(6), planned(10), planned(14), planned(18)],
      nudgePolicy({ maxPerDay: 2, minGapMinutes: 60 }),
    );
    expect(capped).toHaveLength(2);
  });

  it('drops anything too close behind the last one', () => {
    const capped = capPerDay(
      [planned(6), planned(7)],
      nudgePolicy({ maxPerDay: 3, minGapMinutes: 180 }),
    );
    expect(capped).toHaveLength(1);
  });

  it('keeps a nudge far enough behind', () => {
    const capped = capPerDay(
      [planned(6), planned(10)],
      nudgePolicy({ maxPerDay: 3, minGapMinutes: 180 }),
    );
    expect(capped).toHaveLength(2);
  });
});

describe('reading a plan', () => {
  it('filters to one habit', () => {
    const plan = planNudges({
      roster: plain,
      policy: nudgePolicy({ leadMinutes: 240, maxPerDay: 5, minGapMinutes: 1 }),
      results: [
        pending('2025-01-10..2025-01-10'),
        pending('2025-01-10..2025-01-10', 0, 1, stretches),
      ],
      asOf,
    });
    expect(nudgesForHabit(plan, stretches)).toHaveLength(1);
  });

  it('reads back as a line', () => {
    const [nudge] = planNudges({
      roster: plain,
      policy,
      results: [pending('2025-01-10..2025-01-10')],
      asOf,
    });
    expect(nudge && describeNudge(nudge)).toBe('nothing_yet at 2025-01-10T19:00:00.000Z');
  });
});

describe('two habits due at the same minute', () => {
  it('ride along together rather than dropping one', () => {
    const plan = planNudges({
      roster: plain,
      policy,
      results: [
        pending('2025-01-10..2025-01-10'),
        pending('2025-01-10..2025-01-10', 0, 1, stretches),
      ],
      asOf,
    });
    expect(plan).toHaveLength(2);
    expect(plan[0]?.at.epochMs).toBe(plan[1]?.at.epochMs);
  });

  it('still counts as one against the daily cap', () => {
    const plan = planNudges({
      roster: plain,
      policy: nudgePolicy({ leadMinutes: 240, maxPerDay: 1 }),
      results: [
        pending('2025-01-10..2025-01-10'),
        pending('2025-01-10..2025-01-10', 0, 1, stretches),
      ],
      asOf,
    });
    expect(plan).toHaveLength(2);
  });
});
