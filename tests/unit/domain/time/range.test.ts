import { describe, expect, it } from 'vitest';
import {
  chunkRange,
  clampRange,
  compareRanges,
  dateRange,
  daysOf,
  formatDateRange,
  intersectRanges,
  mergeRanges,
  parseDateRange,
  rangeContains,
  rangeCovers,
  rangeLength,
  rangesIntersect,
  rangesTouch,
  shiftRange,
  singleDay,
  subtractRanges,
} from '../../../../src/domain/time/range.js';
import { formatCivilDate, parseCivilDate } from '../../../../src/domain/time/civil.js';

const r = parseDateRange;
const d = parseCivilDate;
const show = (ranges: readonly { from: unknown; to: unknown }[]): string[] =>
  ranges.map((range) => formatDateRange(range as never));

describe('construction', () => {
  it('parses and formats', () => {
    expect(formatDateRange(r('2025-01-06..2025-01-19'))).toBe('2025-01-06..2025-01-19');
  });

  it('refuses a range that ends before it starts', () => {
    expect(() => dateRange(d('2025-01-19'), d('2025-01-06'))).toThrow(/before it starts/);
  });

  it('accepts a single day', () => {
    expect(rangeLength(singleDay(d('2025-01-06')))).toBe(1);
  });

  it('refuses a malformed string', () => {
    expect(() => parseDateRange('2025-01-06')).toThrow(/YYYY-MM-DD\.\.YYYY-MM-DD/);
  });
});

describe('length and membership', () => {
  it('counts both ends', () => {
    expect(rangeLength(r('2025-01-06..2025-01-19'))).toBe(14);
  });

  it('includes the first and last day', () => {
    const hitch = r('2025-01-06..2025-01-19');
    expect(rangeContains(hitch, d('2025-01-06'))).toBe(true);
    expect(rangeContains(hitch, d('2025-01-19'))).toBe(true);
    expect(rangeContains(hitch, d('2025-01-20'))).toBe(false);
    expect(rangeContains(hitch, d('2025-01-05'))).toBe(false);
  });

  it('knows when one range sits inside another', () => {
    expect(rangeCovers(r('2025-01-01..2025-01-31'), r('2025-01-06..2025-01-19'))).toBe(true);
    expect(rangeCovers(r('2025-01-06..2025-01-19'), r('2025-01-01..2025-01-31'))).toBe(false);
  });

  it('lists every day it covers', () => {
    expect(daysOf(r('2025-01-06..2025-01-09')).map(formatCivilDate)).toEqual([
      '2025-01-06',
      '2025-01-07',
      '2025-01-08',
      '2025-01-09',
    ]);
  });
});

describe('overlap', () => {
  it('spots an intersection', () => {
    expect(rangesIntersect(r('2025-01-06..2025-01-19'), r('2025-01-19..2025-02-01'))).toBe(true);
    expect(rangesIntersect(r('2025-01-06..2025-01-19'), r('2025-01-20..2025-02-01'))).toBe(false);
  });

  it('counts abutting ranges as touching but not intersecting', () => {
    expect(rangesTouch(r('2025-01-06..2025-01-19'), r('2025-01-20..2025-02-01'))).toBe(true);
    expect(rangesTouch(r('2025-01-06..2025-01-19'), r('2025-01-21..2025-02-01'))).toBe(false);
  });

  it('returns the overlapping part', () => {
    const overlap = intersectRanges(r('2025-01-06..2025-01-19'), r('2025-01-13..2025-02-01'));
    expect(overlap && formatDateRange(overlap)).toBe('2025-01-13..2025-01-19');
  });

  it('returns nothing when they miss', () => {
    expect(
      intersectRanges(r('2025-01-06..2025-01-19'), r('2025-02-01..2025-02-14')),
    ).toBeUndefined();
  });

  it('clamps to bounds', () => {
    const clamped = clampRange(r('2024-12-20..2025-01-19'), r('2025-01-01..2025-12-31'));
    expect(clamped && formatDateRange(clamped)).toBe('2025-01-01..2025-01-19');
  });
});

describe('shifting and ordering', () => {
  it('moves both ends together', () => {
    expect(formatDateRange(shiftRange(r('2025-01-06..2025-01-19'), 28))).toBe(
      '2025-02-03..2025-02-16',
    );
  });

  it('orders by start then by end', () => {
    expect(compareRanges(r('2025-01-06..2025-01-19'), r('2025-01-07..2025-01-19'))).toBe(-1);
    expect(compareRanges(r('2025-01-06..2025-01-19'), r('2025-01-06..2025-01-12'))).toBe(1);
    expect(compareRanges(r('2025-01-06..2025-01-19'), r('2025-01-06..2025-01-19'))).toBe(0);
  });
});

describe('mergeRanges', () => {
  it('joins overlapping and abutting entries', () => {
    const merged = mergeRanges([
      r('2025-01-13..2025-01-19'),
      r('2025-01-06..2025-01-12'),
      r('2025-02-01..2025-02-07'),
    ]);
    expect(show(merged)).toEqual(['2025-01-06..2025-01-19', '2025-02-01..2025-02-07']);
  });

  it('keeps a range swallowed by a longer one', () => {
    const merged = mergeRanges([r('2025-01-01..2025-01-31'), r('2025-01-10..2025-01-12')]);
    expect(show(merged)).toEqual(['2025-01-01..2025-01-31']);
  });

  it('handles an empty list', () => {
    expect(mergeRanges([])).toEqual([]);
  });
});

describe('subtractRanges', () => {
  it('cuts a hole out of the middle', () => {
    const left = subtractRanges(r('2025-01-01..2025-01-31'), [r('2025-01-10..2025-01-12')]);
    expect(show(left)).toEqual(['2025-01-01..2025-01-09', '2025-01-13..2025-01-31']);
  });

  it('trims from the front and the back', () => {
    const left = subtractRanges(r('2025-01-01..2025-01-31'), [
      r('2024-12-20..2025-01-05'),
      r('2025-01-28..2025-02-10'),
    ]);
    expect(show(left)).toEqual(['2025-01-06..2025-01-27']);
  });

  it('returns nothing when the hole covers everything', () => {
    expect(subtractRanges(r('2025-01-01..2025-01-31'), [r('2024-12-01..2025-12-31')])).toEqual([]);
  });

  it('leaves the range alone when the holes miss', () => {
    const left = subtractRanges(r('2025-01-01..2025-01-31'), [r('2025-06-01..2025-06-30')]);
    expect(show(left)).toEqual(['2025-01-01..2025-01-31']);
  });
});

describe('chunkRange', () => {
  it('splits into equal chunks', () => {
    expect(show(chunkRange(r('2025-01-01..2025-01-12'), 4))).toEqual([
      '2025-01-01..2025-01-04',
      '2025-01-05..2025-01-08',
      '2025-01-09..2025-01-12',
    ]);
  });

  it('leaves a short chunk at the end', () => {
    expect(show(chunkRange(r('2025-01-01..2025-01-10'), 4))).toEqual([
      '2025-01-01..2025-01-04',
      '2025-01-05..2025-01-08',
      '2025-01-09..2025-01-10',
    ]);
  });

  it('refuses a chunk size below one', () => {
    expect(() => chunkRange(r('2025-01-01..2025-01-10'), 0)).toThrow(/above zero/);
  });
});
