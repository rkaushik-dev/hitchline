import { describe, expect, it } from 'vitest';
import {
  asOf,
  describeRevision,
  effectiveOn,
  historyOf,
  latest,
  revise,
  versionCount,
} from '../../../../src/domain/habit/revision.js';
import { formatCadence, parseCadence } from '../../../../src/domain/habit/cadence.js';
import { parseTarget } from '../../../../src/domain/habit/target.js';
import { parseCivilDate } from '../../../../src/domain/time/civil.js';
import { ConflictError, ValidationError } from '../../../../src/domain/errors.js';
import { aHabit } from '../../../support/fixtures.js';

const d = parseCivilDate;
const habit = aHabit({ cadence: '1/onshift-day', startsOn: '2025-01-06' });

describe('the first revision', () => {
  it('starts on the day the habit did', () => {
    const history = historyOf(habit);
    expect(versionCount(history)).toBe(1);
    expect(latest(history)?.from).toEqual(d('2025-01-06'));
    expect(latest(history)?.version).toBe(1);
  });

  it('carries the cadence the habit was made with', () => {
    expect(formatCadence(latest(historyOf(habit))!.cadence)).toBe('1/onshift-day');
  });
});

describe('revising', () => {
  const history = historyOf(habit);

  it('adds a version', () => {
    const revised = revise(history, { from: d('2025-03-01'), cadence: parseCadence('3/hitch') });
    expect(versionCount(revised)).toBe(2);
    expect(latest(revised)?.version).toBe(2);
  });

  it('leaves the history it was given alone', () => {
    revise(history, { from: d('2025-03-01'), cadence: parseCadence('3/hitch') });
    expect(versionCount(history)).toBe(1);
  });

  it('carries the grace window forward when none is given', () => {
    const withGrace = historyOf(aHabit({ graceDays: 2, startsOn: '2025-01-06' }));
    const revised = revise(withGrace, {
      from: d('2025-03-01'),
      cadence: parseCadence('3/hitch'),
    });
    expect(latest(revised)?.graceDays).toBe(2);
  });

  it('refuses a revision that starts before the one in force', () => {
    expect(() =>
      revise(history, { from: d('2024-12-01'), cadence: parseCadence('3/hitch') }),
    ).toThrow(ValidationError);
  });

  it('refuses two revisions starting on the same day', () => {
    expect(() =>
      revise(history, { from: d('2025-01-06'), cadence: parseCadence('3/hitch') }),
    ).toThrow(ConflictError);
  });

  it('bounds the note and the grace window', () => {
    expect(() =>
      revise(history, {
        from: d('2025-03-01'),
        cadence: parseCadence('3/hitch'),
        note: 'x'.repeat(241),
      }),
    ).toThrow(/at most 240/);
    expect(() =>
      revise(history, { from: d('2025-03-01'), cadence: parseCadence('3/hitch'), graceDays: 9 }),
    ).toThrow(/between 0 and 7/);
  });
});

describe('reading a history back', () => {
  const history = revise(
    revise(historyOf(habit), { from: d('2025-03-01'), cadence: parseCadence('3/hitch') }),
    {
      from: d('2025-06-01'),
      cadence: parseCadence('1/day'),
      target: parseTarget('at least 20 minutes'),
    },
  );

  it('finds nothing before the habit started', () => {
    expect(effectiveOn(history, d('2025-01-05'))).toBeUndefined();
  });

  it('finds the first revision on the day it started', () => {
    expect(effectiveOn(history, d('2025-01-06'))?.version).toBe(1);
  });

  it('holds a revision until the next one starts', () => {
    expect(effectiveOn(history, d('2025-02-28'))?.version).toBe(1);
    expect(effectiveOn(history, d('2025-03-01'))?.version).toBe(2);
    expect(effectiveOn(history, d('2025-05-31'))?.version).toBe(2);
    expect(effectiveOn(history, d('2025-06-01'))?.version).toBe(3);
  });

  it('holds the last revision forever after', () => {
    expect(effectiveOn(history, d('2030-01-01'))?.version).toBe(3);
  });
});

describe('scoring against a date', () => {
  const history = revise(historyOf(habit), {
    from: d('2025-03-01'),
    cadence: parseCadence('3/hitch'),
    target: parseTarget('at least 20 minutes'),
  });

  it('reads the cadence in force that day', () => {
    expect(formatCadence(asOf(habit, history, d('2025-02-01')).cadence)).toBe('1/onshift-day');
    expect(formatCadence(asOf(habit, history, d('2025-03-02')).cadence)).toBe('3/hitch');
  });

  it('reads the target in force that day', () => {
    expect(asOf(habit, history, d('2025-02-01')).target).toBeUndefined();
    expect(asOf(habit, history, d('2025-03-02')).target?.amount).toBe(20);
  });

  it('leaves the habit alone before any revision applies', () => {
    expect(asOf(habit, history, d('2024-12-01'))).toBe(habit);
  });

  it('keeps everything else about the habit', () => {
    const scored = asOf(habit, history, d('2025-03-02'));
    expect(scored.id).toBe(habit.id);
    expect(scored.name).toBe(habit.name);
    expect(scored.startsOn).toEqual(habit.startsOn);
  });
});

describe('describing a revision', () => {
  it('reads back with its version and cadence', () => {
    expect(describeRevision(latest(historyOf(habit))!)).toBe(
      'v1 from 2025-01-06: 1/onshift-day (as created)',
    );
  });

  it('includes a target when there is one', () => {
    const history = revise(historyOf(habit), {
      from: d('2025-03-01'),
      cadence: parseCadence('1/day'),
      target: parseTarget('at least 20 minutes'),
    });
    expect(describeRevision(latest(history)!)).toBe(
      'v2 from 2025-03-01: 1/day, at least 20 minutes',
    );
  });
});
