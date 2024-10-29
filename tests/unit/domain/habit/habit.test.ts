import { describe, expect, it } from 'vitest';
import {
  checkHabitName,
  checkTags,
  describeHabit,
  habit,
  hasTag,
  isRunningOn,
  moveHabitTo,
  recadence,
  renameHabit,
  retag,
  retarget,
  type HabitInput,
} from '../../../../src/domain/habit/habit.js';
import { parseCadence } from '../../../../src/domain/habit/cadence.js';
import { parseTarget } from '../../../../src/domain/habit/target.js';
import { crewMemberId, habitId } from '../../../../src/domain/ids.js';
import { parseCivilDate } from '../../../../src/domain/time/civil.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';

const d = parseCivilDate;

const input: HabitInput = {
  id: habitId('hab_0123456789ABCDEF'),
  memberId: crewMemberId('mem_0123456789ABCDEF'),
  name: 'Twenty minutes on the treadmill',
  cadence: parseCadence('1/onshift-day'),
  startsOn: d('2025-01-06'),
  createdAt: parseInstant('2025-01-04T09:00:00Z'),
};

describe('names and tags', () => {
  it('squeezes whitespace in a name', () => {
    expect(checkHabitName('  Twenty   minutes ')).toBe('Twenty minutes');
  });

  it('rejects a name that is too short or too long', () => {
    expect(() => checkHabitName('go')).toThrow(/at least 3 characters/);
    expect(() => checkHabitName('x'.repeat(101))).toThrow(/at most 100 characters/);
  });

  it('lowercases, sorts and dedupes tags', () => {
    expect(checkTags(['Fitness', 'sleep', 'fitness'])).toEqual(['fitness', 'sleep']);
  });

  it('rejects a tag with spaces or punctuation', () => {
    expect(() => checkTags(['off shift'])).toThrow(/unusable tag/);
    expect(() => checkTags(['-leading'])).toThrow(/unusable tag/);
  });

  it('rejects too many tags', () => {
    expect(() => checkTags(['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i'])).toThrow(/at most 8/);
  });
});

describe('creating a habit', () => {
  it('defaults to active with no target and no grace', () => {
    const entry = habit(input);
    expect(entry.status).toBe('active');
    expect(entry.target).toBeUndefined();
    expect(entry.graceDays).toBe(0);
    expect(entry.tags).toEqual([]);
  });

  it('keeps the target when one is given', () => {
    const entry = habit({ ...input, target: parseTarget('at least 20 minutes') });
    expect(entry.target?.amount).toBe(20);
  });

  it('rejects an end date before the start', () => {
    expect(() => habit({ ...input, endsOn: d('2025-01-01') })).toThrow(/before the habit starts/);
  });

  it('rejects a grace window outside the allowed range', () => {
    expect(() => habit({ ...input, graceDays: 8 })).toThrow(/between 0 and 7/);
    expect(() => habit({ ...input, graceDays: -1 })).toThrow(/between 0 and 7/);
  });
});

describe('when a habit is running', () => {
  it('is not running before it starts', () => {
    expect(isRunningOn(habit(input), d('2025-01-05'))).toBe(false);
    expect(isRunningOn(habit(input), d('2025-01-06'))).toBe(true);
  });

  it('stops after the end date', () => {
    const bounded = habit({ ...input, endsOn: d('2025-03-31') });
    expect(isRunningOn(bounded, d('2025-03-31'))).toBe(true);
    expect(isRunningOn(bounded, d('2025-04-01'))).toBe(false);
  });

  it('runs forever when no end date is set', () => {
    expect(isRunningOn(habit(input), d('2030-01-01'))).toBe(true);
  });

  it('is not running while paused or archived', () => {
    const paused = moveHabitTo(habit(input), 'paused');
    expect(isRunningOn(paused, d('2025-02-01'))).toBe(false);
    expect(isRunningOn(moveHabitTo(paused, 'archived'), d('2025-02-01'))).toBe(false);
  });
});

describe('status moves', () => {
  const entry = habit(input);

  it('pauses and resumes', () => {
    const paused = moveHabitTo(entry, 'paused');
    expect(paused.status).toBe('paused');
    expect(moveHabitTo(paused, 'active').status).toBe('active');
  });

  it('treats a move to the same status as a no op', () => {
    expect(moveHabitTo(entry, 'active')).toBe(entry);
  });

  it('refuses to bring back an archived habit', () => {
    const archived = moveHabitTo(entry, 'archived');
    expect(() => moveHabitTo(archived, 'active')).toThrow(/cannot go from archived to active/);
  });
});

describe('edits', () => {
  const entry = habit(input);

  it('renames', () => {
    expect(renameHabit(entry, 'Thirty minutes on the bike').name).toBe(
      'Thirty minutes on the bike',
    );
  });

  it('changes the target and clears it', () => {
    const targeted = retarget(entry, parseTarget('at least 30 minutes'));
    expect(targeted.target?.amount).toBe(30);
    expect(retarget(targeted, undefined).target).toBeUndefined();
  });

  it('changes the cadence', () => {
    expect(recadence(entry, parseCadence('3/hitch')).cadence).toEqual(parseCadence('3/hitch'));
  });

  it('refuses edits to an archived habit', () => {
    const archived = moveHabitTo(entry, 'archived');
    expect(() => recadence(archived, parseCadence('3/hitch'))).toThrow(/archived habit/);
    expect(() => retarget(archived, undefined)).toThrow(/archived habit/);
  });

  it('retags and reads tags back', () => {
    const tagged = retag(entry, ['Fitness', 'cardio']);
    expect(tagged.tags).toEqual(['cardio', 'fitness']);
    expect(hasTag(tagged, 'Fitness')).toBe(true);
    expect(hasTag(tagged, 'sleep')).toBe(false);
  });
});

describe('describing a habit', () => {
  it('reads back the cadence and target', () => {
    const entry = habit({ ...input, target: parseTarget('at least 20 minutes') });
    expect(describeHabit(entry)).toBe(
      'Twenty minutes on the treadmill 1/onshift-day, at least 20 minutes',
    );
  });

  it('leaves the target out when there is none', () => {
    expect(describeHabit(habit(input))).toBe('Twenty minutes on the treadmill 1/onshift-day');
  });
});
