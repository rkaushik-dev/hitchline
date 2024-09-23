import { describe, expect, it } from 'vitest';
import {
  addDays,
  addMonths,
  civilDate,
  compareCivil,
  dayCount,
  dayOfWeek,
  daysBetween,
  daysInMonth,
  eachDay,
  endOfIsoWeek,
  endOfMonth,
  formatCivilDate,
  fromEpochDay,
  isAfter,
  isBefore,
  isLeapYear,
  isSameDay,
  isWeekend,
  isWithin,
  maxDate,
  minDate,
  parseCivilDate,
  startOfIsoWeek,
  startOfMonth,
  toEpochDay,
} from '../../../../src/domain/time/civil.js';
import { ValidationError } from '../../../../src/domain/errors.js';

const d = parseCivilDate;

describe('construction', () => {
  it('accepts a real date', () => {
    expect(civilDate(2025, 2, 28)).toEqual({ year: 2025, month: 2, day: 28 });
  });

  it('accepts the leap day only in a leap year', () => {
    expect(() => civilDate(2024, 2, 29)).not.toThrow();
    expect(() => civilDate(2025, 2, 29)).toThrow(ValidationError);
  });

  it('knows the century rule for leap years', () => {
    expect(isLeapYear(1900)).toBe(false);
    expect(isLeapYear(2000)).toBe(true);
    expect(isLeapYear(2024)).toBe(true);
    expect(isLeapYear(2025)).toBe(false);
  });

  it('rejects a month outside the year', () => {
    expect(() => civilDate(2025, 13, 1)).toThrow(/between 1 and 12/);
    expect(() => daysInMonth(2025, 0)).toThrow(/between 1 and 12/);
  });

  it('rejects fractional parts', () => {
    expect(() => civilDate(2025, 1, 1.5)).toThrow(/whole numbers/);
  });

  it('rejects a year outside the supported range', () => {
    expect(() => civilDate(1899, 1, 1)).toThrow(/between 1900 and 2999/);
  });
});

describe('parsing and formatting', () => {
  it('round trips an iso date', () => {
    expect(formatCivilDate(parseCivilDate('2025-03-09'))).toBe('2025-03-09');
  });

  it('pads single digit months and days', () => {
    expect(formatCivilDate(civilDate(2025, 4, 7))).toBe('2025-04-07');
  });

  it('refuses anything that is not a plain iso date', () => {
    expect(() => parseCivilDate('9 March 2025')).toThrow(/YYYY-MM-DD/);
    expect(() => parseCivilDate('2025-3-9')).toThrow(/YYYY-MM-DD/);
    expect(() => parseCivilDate('2025-03-09T00:00:00Z')).toThrow(/YYYY-MM-DD/);
  });

  it('still validates a well shaped but impossible date', () => {
    expect(() => parseCivilDate('2025-02-30')).toThrow(/between 1 and 28/);
  });
});

describe('epoch days', () => {
  it('puts the epoch at zero', () => {
    expect(toEpochDay(d('1970-01-01'))).toBe(0);
    expect(fromEpochDay(0)).toEqual(d('1970-01-01'));
  });

  it('counts backwards before the epoch', () => {
    expect(toEpochDay(d('1969-12-31'))).toBe(-1);
    expect(fromEpochDay(-1)).toEqual(d('1969-12-31'));
  });

  it('round trips across a leap day', () => {
    const leap = d('2024-02-29');
    expect(fromEpochDay(toEpochDay(leap))).toEqual(leap);
  });

  it('rejects a fractional epoch day', () => {
    expect(() => fromEpochDay(1.5)).toThrow(/whole number of days/);
  });
});

describe('arithmetic', () => {
  it('adds days across a month boundary', () => {
    expect(addDays(d('2025-01-30'), 3)).toEqual(d('2025-02-02'));
  });

  it('subtracts days across a year boundary', () => {
    expect(addDays(d('2025-01-02'), -3)).toEqual(d('2024-12-30'));
  });

  it('clamps the day when adding months lands on a short one', () => {
    expect(addMonths(d('2025-01-31'), 1)).toEqual(d('2025-02-28'));
    expect(addMonths(d('2024-01-31'), 1)).toEqual(d('2024-02-29'));
  });

  it('walks backwards over months', () => {
    expect(addMonths(d('2025-03-15'), -4)).toEqual(d('2024-11-15'));
  });

  it('measures the gap between two dates', () => {
    expect(daysBetween(d('2025-01-01'), d('2025-01-15'))).toBe(14);
    expect(daysBetween(d('2025-01-15'), d('2025-01-01'))).toBe(-14);
  });

  it('counts an inclusive span', () => {
    expect(dayCount(d('2025-01-01'), d('2025-01-14'))).toBe(14);
    expect(dayCount(d('2025-01-14'), d('2025-01-01'))).toBe(0);
  });
});

describe('comparison', () => {
  it('orders dates', () => {
    expect(compareCivil(d('2025-01-01'), d('2025-01-02'))).toBe(-1);
    expect(compareCivil(d('2025-01-02'), d('2025-01-01'))).toBe(1);
    expect(compareCivil(d('2025-01-01'), d('2025-01-01'))).toBe(0);
  });

  it('answers the readable questions', () => {
    expect(isBefore(d('2025-01-01'), d('2025-01-02'))).toBe(true);
    expect(isAfter(d('2025-01-01'), d('2025-01-02'))).toBe(false);
    expect(isSameDay(d('2025-01-01'), d('2025-01-01'))).toBe(true);
  });

  it('treats a range as closed at both ends', () => {
    expect(isWithin(d('2025-01-01'), d('2025-01-01'), d('2025-01-14'))).toBe(true);
    expect(isWithin(d('2025-01-14'), d('2025-01-01'), d('2025-01-14'))).toBe(true);
    expect(isWithin(d('2025-01-15'), d('2025-01-01'), d('2025-01-14'))).toBe(false);
  });

  it('picks the earlier and later of a pair', () => {
    expect(minDate(d('2025-05-01'), d('2025-04-01'))).toEqual(d('2025-04-01'));
    expect(maxDate(d('2025-05-01'), d('2025-04-01'))).toEqual(d('2025-05-01'));
  });
});

describe('weekdays', () => {
  it('numbers monday as one and sunday as seven', () => {
    expect(dayOfWeek(d('2025-03-10'))).toBe(1);
    expect(dayOfWeek(d('2025-03-16'))).toBe(7);
  });

  it('works before the epoch', () => {
    expect(dayOfWeek(d('1969-12-31'))).toBe(3);
  });

  it('flags saturday and sunday', () => {
    expect(isWeekend(d('2025-03-15'))).toBe(true);
    expect(isWeekend(d('2025-03-16'))).toBe(true);
    expect(isWeekend(d('2025-03-17'))).toBe(false);
  });

  it('finds the ends of an iso week', () => {
    expect(startOfIsoWeek(d('2025-03-13'))).toEqual(d('2025-03-10'));
    expect(endOfIsoWeek(d('2025-03-13'))).toEqual(d('2025-03-16'));
    expect(startOfIsoWeek(d('2025-03-16'))).toEqual(d('2025-03-10'));
  });
});

describe('month edges', () => {
  it('finds the first and last day', () => {
    expect(startOfMonth(d('2025-02-14'))).toEqual(d('2025-02-01'));
    expect(endOfMonth(d('2025-02-14'))).toEqual(d('2025-02-28'));
    expect(endOfMonth(d('2024-02-14'))).toEqual(d('2024-02-29'));
  });
});

describe('eachDay', () => {
  it('walks an inclusive range', () => {
    const walked = [...eachDay(d('2025-01-01'), d('2025-01-04'))].map(formatCivilDate);
    expect(walked).toEqual(['2025-01-01', '2025-01-02', '2025-01-03', '2025-01-04']);
  });

  it('yields a single day when both ends match', () => {
    expect([...eachDay(d('2025-01-01'), d('2025-01-01'))]).toHaveLength(1);
  });

  it('yields nothing when the range is reversed', () => {
    expect([...eachDay(d('2025-01-04'), d('2025-01-01'))]).toEqual([]);
  });
});
