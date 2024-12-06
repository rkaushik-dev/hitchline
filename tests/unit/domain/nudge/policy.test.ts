import { describe, expect, it } from 'vitest';
import {
  DEFAULT_POLICY,
  describePolicy,
  firstAllowedTime,
  isQuiet,
  isSilent,
  nudgePolicy,
  nudgesOnRole,
  quietMinutesAhead,
  shiftedBy,
  withChannel,
} from '../../../../src/domain/nudge/policy.js';
import { formatClockTime, parseClockTime } from '../../../../src/domain/time/clocktime.js';

const t = parseClockTime;

describe('the default policy', () => {
  it('is quiet overnight and pings three times a day', () => {
    expect(DEFAULT_POLICY.maxPerDay).toBe(3);
    expect(describePolicy(DEFAULT_POLICY)).toBe('3 a day by push, quiet 22:00-06:00 overnight');
  });

  it('does not nudge on a travel day', () => {
    expect(nudgesOnRole(DEFAULT_POLICY, 'travel')).toBe(false);
    expect(nudgesOnRole(DEFAULT_POLICY, 'onshift')).toBe(true);
  });
});

describe('building a policy', () => {
  it('takes the defaults when given nothing', () => {
    expect(nudgePolicy()).toEqual(DEFAULT_POLICY);
  });

  it('reads quiet hours from a string', () => {
    expect(formatClockTime(nudgePolicy({ quietHours: '23:00-05:30' }).quietHours.to)).toBe('05:30');
  });

  it('bounds the numbers', () => {
    expect(() => nudgePolicy({ minGapMinutes: 0 })).toThrow(/between 1 and 1440/);
    expect(() => nudgePolicy({ maxPerDay: 13 })).toThrow(/between 0 and 12/);
    expect(() => nudgePolicy({ leadMinutes: -1 })).toThrow(/between 0 and 4320/);
  });

  it('checks the roles it is asked to nudge on', () => {
    expect(() => nudgePolicy({ roles: ['ashore'] })).toThrow(/does not name a day role/);
    expect(() => nudgePolicy({ roles: [] })).toThrow(/at least one day role/);
  });

  it('dedupes and orders the roles', () => {
    expect(nudgePolicy({ roles: ['offshift', 'onshift', 'onshift'] }).roles).toEqual([
      'onshift',
      'offshift',
    ]);
  });
});

describe('quiet hours', () => {
  const policy = nudgePolicy({ quietHours: '22:00-06:00' });

  it('covers the overnight window', () => {
    expect(isQuiet(policy, t('23:30'))).toBe(true);
    expect(isQuiet(policy, t('02:00'))).toBe(true);
    expect(isQuiet(policy, t('06:00'))).toBe(false);
    expect(isQuiet(policy, t('21:59'))).toBe(false);
  });

  it('works for a daytime quiet window too', () => {
    const daytime = nudgePolicy({ quietHours: '13:00-15:00' });
    expect(isQuiet(daytime, t('14:00'))).toBe(true);
    expect(isQuiet(daytime, t('16:00'))).toBe(false);
  });

  it('pushes a nudge to the end of the quiet window', () => {
    expect(formatClockTime(firstAllowedTime(policy, t('23:30')))).toBe('06:00');
    expect(formatClockTime(firstAllowedTime(policy, t('02:00')))).toBe('06:00');
  });

  it('leaves an allowed time alone', () => {
    expect(formatClockTime(firstAllowedTime(policy, t('09:00')))).toBe('09:00');
  });

  it('measures how long the wait is', () => {
    expect(quietMinutesAhead(policy, t('23:30'))).toBe(390);
    expect(quietMinutesAhead(policy, t('05:30'))).toBe(30);
    expect(quietMinutesAhead(policy, t('09:00'))).toBe(0);
  });
});

describe('silence', () => {
  it('is silent with no channel', () => {
    expect(isSilent(withChannel(DEFAULT_POLICY, 'none'))).toBe(true);
  });

  it('is silent with a daily cap of zero', () => {
    expect(isSilent(nudgePolicy({ maxPerDay: 0 }))).toBe(true);
  });

  it('is not silent otherwise', () => {
    expect(isSilent(DEFAULT_POLICY)).toBe(false);
  });
});

describe('shifting a time', () => {
  it('moves forward and wraps', () => {
    expect(formatClockTime(shiftedBy(t('22:00'), 480))).toBe('06:00');
  });
});
