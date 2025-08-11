import { describe, expect, it } from 'vitest';
import {
  describeBreach,
  describeQuota,
  headroom,
  isBreached,
  quota,
  readQuota,
} from '../../../../src/domain/adherence/quota.js';
import { aResult as result } from '../../../support/builders.js';
import { ValidationError } from '../../../../src/domain/errors.js';

const twoInSeven = quota('weekly slip', 2, 7);

describe('building a quota', () => {
  it('keeps the name, the allowance and the window', () => {
    expect(twoInSeven).toEqual({
      name: 'weekly slip',
      allowed: 2,
      windowDays: 7,
      countPartial: true,
    });
  });

  it('refuses an empty name', () => {
    expect(() => quota('  ', 2, 7)).toThrow(ValidationError);
  });

  it('refuses a negative allowance', () => {
    expect(() => quota('weekly', -1, 7)).toThrow(/zero or more/);
  });

  it('bounds the window', () => {
    expect(() => quota('weekly', 1, 0)).toThrow(/between 1 and 365/);
    expect(() => quota('weekly', 1, 400)).toThrow(/between 1 and 365/);
  });

  it('describes itself', () => {
    expect(describeQuota(twoInSeven)).toBe('weekly slip: at most 2 misses or partials in 7 days');
    expect(describeQuota(quota('hard', 0, 30, false))).toBe('hard: at most 0 misses in 30 days');
  });
});

describe('reading a quota', () => {
  it('is clean when nothing was dropped', () => {
    const reading = readQuota(twoInSeven, [result('2025-01-06', 'met')]);
    expect(isBreached(reading)).toBe(false);
    expect(reading.worst).toBe(0);
    expect(headroom(reading)).toBe(2);
  });

  it('is clean at the allowance', () => {
    const reading = readQuota(twoInSeven, [
      result('2025-01-06', 'missed'),
      result('2025-01-07', 'missed'),
    ]);
    expect(isBreached(reading)).toBe(false);
    expect(reading.worst).toBe(2);
    expect(headroom(reading)).toBe(0);
  });

  it('bites one over the allowance', () => {
    const reading = readQuota(twoInSeven, [
      result('2025-01-06', 'missed'),
      result('2025-01-07', 'missed'),
      result('2025-01-08', 'missed'),
    ]);
    expect(isBreached(reading)).toBe(true);
    expect(reading.breaches[0]?.counted).toBe(3);
  });

  it('does not join two stretches that are far enough apart', () => {
    const reading = readQuota(twoInSeven, [
      result('2025-01-06', 'missed'),
      result('2025-01-07', 'missed'),
      result('2025-02-06', 'missed'),
      result('2025-02-07', 'missed'),
    ]);
    expect(isBreached(reading)).toBe(false);
  });

  it('counts a partial by default and not when told otherwise', () => {
    const results = [
      result('2025-01-06', 'missed'),
      result('2025-01-07', 'partial'),
      result('2025-01-08', 'partial'),
    ];
    expect(isBreached(readQuota(twoInSeven, results))).toBe(true);
    expect(isBreached(readQuota(quota('hard misses', 2, 7, false), results))).toBe(false);
  });

  it('ignores anything still pending', () => {
    const reading = readQuota(quota('tight', 0, 7), [result('2025-01-06', 'pending')]);
    expect(isBreached(reading)).toBe(false);
  });

  it('reports one breach for a run rather than one per day', () => {
    const reading = readQuota(quota('tight', 1, 7), [
      result('2025-01-06', 'missed'),
      result('2025-01-07', 'missed'),
      result('2025-01-08', 'missed'),
    ]);
    expect(reading.breaches).toHaveLength(1);
  });

  it('reads results in any order', () => {
    const reading = readQuota(quota('tight', 1, 7), [
      result('2025-01-08', 'missed'),
      result('2025-01-06', 'missed'),
    ]);
    expect(reading.worst).toBe(2);
  });

  it('describes a breach with its window', () => {
    const reading = readQuota(quota('tight', 1, 7), [
      result('2025-01-06', 'missed'),
      result('2025-01-07', 'missed'),
    ]);
    expect(describeBreach(reading.breaches[0]!)).toBe(
      'tight broken 2025-01-06 to 2025-01-12 with 2',
    );
  });
});

describe('a one day window', () => {
  it('only ever sees one day at a time', () => {
    const reading = readQuota(quota('daily', 0, 1), [
      result('2025-01-06', 'missed'),
      result('2025-01-07', 'missed'),
    ]);
    expect(reading.breaches).toHaveLength(2);
    expect(reading.worst).toBe(1);
  });
});
