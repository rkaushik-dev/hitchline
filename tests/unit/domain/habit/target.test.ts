import { describe, expect, it } from 'vitest';
import {
  formatTarget,
  habitTarget,
  isTargetUnit,
  meetsTarget,
  parseTarget,
  roundToUnit,
  sumReadings,
  targetProgress,
  unitFor,
} from '../../../../src/domain/habit/target.js';

describe('units', () => {
  it('recognises the units it tracks', () => {
    expect(isTargetUnit('minutes')).toBe(true);
    expect(isTargetUnit('furlongs')).toBe(false);
  });

  it('reads the short words people type', () => {
    expect(unitFor('min')).toBe('minutes');
    expect(unitFor('KM')).toBe('kilometres');
    expect(unitFor(' times ')).toBe('count');
  });

  it('refuses a unit it does not track', () => {
    expect(() => unitFor('furlongs')).toThrow(/not a unit this tracks/);
  });

  it('keeps whole numbers for counting units', () => {
    expect(roundToUnit('count', 3.4)).toBe(3);
    expect(roundToUnit('minutes', 30.6)).toBe(31);
  });

  it('keeps two places for measured units', () => {
    expect(roundToUnit('kilometres', 5.238)).toBe(5.24);
    expect(roundToUnit('litres', 1.005)).toBe(1.01);
  });
});

describe('building a target', () => {
  it('defaults to at least', () => {
    expect(habitTarget('minutes', 30)).toEqual({
      unit: 'minutes',
      amount: 30,
      comparison: 'at_least',
    });
  });

  it('refuses a zero or negative amount', () => {
    expect(() => habitTarget('minutes', 0)).toThrow(/positive number/);
    expect(() => habitTarget('minutes', -5)).toThrow(/positive number/);
  });

  it('refuses an amount that rounds away to nothing', () => {
    expect(() => habitTarget('count', 0.2)).toThrow(/rounds to nothing/);
  });

  it('refuses an absurd amount', () => {
    expect(() => habitTarget('grams', 100_001)).toThrow(/at most 100000/);
  });
});

describe('parsing', () => {
  it('reads an at least target', () => {
    expect(parseTarget('at least 30 minutes')).toEqual(habitTarget('minutes', 30));
  });

  it('reads an at most target', () => {
    expect(parseTarget('at most 2 litres')).toEqual(habitTarget('litres', 2, 'at_most'));
  });

  it('reads a decimal amount', () => {
    expect(parseTarget('at least 5.5 km').amount).toBe(5.5);
  });

  it('refuses a target it cannot read', () => {
    expect(() => parseTarget('30 minutes')).toThrow(/must look like at least 30 minutes/);
    expect(() => parseTarget('roughly 30 minutes')).toThrow(/must look like/);
  });

  it('round trips', () => {
    expect(formatTarget(parseTarget('at most 2 litres'))).toBe('at most 2 litres');
    expect(formatTarget(parseTarget('at least 30 min'))).toBe('at least 30 minutes');
  });
});

describe('meeting a target', () => {
  const half = habitTarget('minutes', 30);
  const limit = habitTarget('litres', 2, 'at_most');

  it('is met at the boundary', () => {
    expect(meetsTarget(half, 30)).toBe(true);
    expect(meetsTarget(limit, 2)).toBe(true);
  });

  it('is not met below an at least target', () => {
    expect(meetsTarget(half, 29)).toBe(false);
  });

  it('is not met above an at most target', () => {
    expect(meetsTarget(limit, 2.01)).toBe(false);
  });

  it('rounds the reading before comparing', () => {
    expect(meetsTarget(half, 29.6)).toBe(true);
    expect(meetsTarget(limit, 2.004)).toBe(true);
  });
});

describe('progress', () => {
  it('runs from zero to one for an at least target', () => {
    const target = habitTarget('minutes', 40);
    expect(targetProgress(target, 0)).toBe(0);
    expect(targetProgress(target, 10)).toBe(0.25);
    expect(targetProgress(target, 40)).toBe(1);
  });

  it('does not go past one', () => {
    expect(targetProgress(habitTarget('minutes', 40), 400)).toBe(1);
  });

  it('is all or nothing for an at most target', () => {
    const target = habitTarget('litres', 2, 'at_most');
    expect(targetProgress(target, 1)).toBe(1);
    expect(targetProgress(target, 3)).toBe(0);
  });
});

describe('summing readings', () => {
  it('adds and rounds once at the end', () => {
    expect(sumReadings('kilometres', [1.234, 2.345, 0.001])).toBe(3.58);
  });

  it('sums nothing to zero', () => {
    expect(sumReadings('minutes', [])).toBe(0);
  });
});
