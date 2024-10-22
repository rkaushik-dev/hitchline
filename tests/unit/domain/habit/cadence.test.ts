import { describe, expect, it } from 'vitest';
import {
  formatCadence,
  isRoleBound,
  parseCadence,
  perBlock,
  perDay,
  perHitch,
  perRoleDay,
  perWindow,
  rolesTouched,
} from '../../../../src/domain/habit/cadence.js';

describe('building a cadence', () => {
  it('counts times per calendar day', () => {
    expect(perDay(2)).toEqual({ kind: 'per_day', times: 2 });
  });

  it('counts times per day of a role', () => {
    expect(perRoleDay(['onshift'], 1)).toEqual({
      kind: 'per_role_day',
      roles: ['onshift'],
      times: 1,
    });
  });

  it('drops a repeated role', () => {
    expect(perRoleDay(['onshift', 'onshift', 'travel'], 1).roles).toEqual(['onshift', 'travel']);
  });

  it('refuses a cadence with no roles', () => {
    expect(() => perRoleDay([], 1)).toThrow(/at least one day role/);
  });

  it('refuses zero or fractional times', () => {
    expect(() => perDay(0)).toThrow(/above zero/);
    expect(() => perHitch(1.5)).toThrow(/whole number/);
  });

  it('refuses an unreasonable number of times', () => {
    expect(() => perDay(49)).toThrow(/at most 48/);
  });

  it('bounds the rolling window', () => {
    expect(() => perWindow(3, 1)).toThrow(/between 2 and 120/);
    expect(() => perWindow(3, 121)).toThrow(/between 2 and 120/);
    expect(perWindow(3, 14).windowDays).toBe(14);
  });
});

describe('parsing the short forms', () => {
  it('reads a calendar cadence', () => {
    expect(parseCadence('2/day')).toEqual(perDay(2));
  });

  it('reads a hitch cadence', () => {
    expect(parseCadence('3/hitch')).toEqual(perHitch(3));
  });

  it('reads a role day cadence', () => {
    expect(parseCadence('1/onshift-day')).toEqual(perRoleDay(['onshift'], 1));
  });

  it('accepts the short role words', () => {
    expect(parseCadence('1/on-day')).toEqual(perRoleDay(['onshift'], 1));
    expect(parseCadence('1/off-day')).toEqual(perRoleDay(['offshift'], 1));
  });

  it('reads several roles joined together', () => {
    expect(parseCadence('1/onshift+standby-day')).toEqual(perRoleDay(['onshift', 'standby'], 1));
  });

  it('reads a block cadence', () => {
    expect(parseCadence('1/onshift-block')).toEqual(perBlock('onshift', 1));
  });

  it('reads a rolling window cadence', () => {
    expect(parseCadence('5/14d')).toEqual(perWindow(5, 14));
  });

  it('ignores case and surrounding space', () => {
    expect(parseCadence('  3/HITCH ')).toEqual(perHitch(3));
  });
});

describe('rejecting nonsense', () => {
  it('refuses a string with no slash', () => {
    expect(() => parseCadence('every day')).toThrow(/must look like 3\/hitch/);
  });

  it('refuses an unknown unit', () => {
    expect(() => parseCadence('2/fortnight')).toThrow(/does not know the unit fortnight/);
  });

  it('refuses a role word it does not recognise', () => {
    expect(() => parseCadence('2/ashore-day')).toThrow(/does not name a day role: ashore/);
  });

  it('still bounds the window when parsed', () => {
    expect(() => parseCadence('2/500d')).toThrow(/between 2 and 120/);
  });
});

describe('formatting', () => {
  it('round trips every shape', () => {
    for (const text of [
      '2/day',
      '3/hitch',
      '1/onshift-day',
      '1/onshift+offshift-day',
      '2/travel-block',
      '5/14d',
    ]) {
      expect(formatCadence(parseCadence(text))).toBe(text);
    }
  });

  it('normalises the short role words on the way out', () => {
    expect(formatCadence(parseCadence('1/on-day'))).toBe('1/onshift-day');
  });
});

describe('what a cadence depends on', () => {
  it('knows which shapes are tied to a role', () => {
    expect(isRoleBound(perRoleDay(['onshift'], 1))).toBe(true);
    expect(isRoleBound(perBlock('offshift', 1))).toBe(true);
    expect(isRoleBound(perDay(1))).toBe(false);
    expect(isRoleBound(perHitch(1))).toBe(false);
    expect(isRoleBound(perWindow(1, 7))).toBe(false);
  });

  it('lists the roles it reads', () => {
    expect(rolesTouched(perRoleDay(['onshift', 'travel'], 1))).toEqual(['onshift', 'travel']);
    expect(rolesTouched(perBlock('offshift', 1))).toEqual(['offshift']);
    expect(rolesTouched(perDay(1))).toEqual([]);
  });
});
