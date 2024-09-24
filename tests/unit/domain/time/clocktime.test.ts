import { describe, expect, it } from 'vitest';
import {
  MIDNIGHT,
  MINUTES_IN_DAY,
  addMinutes,
  clockRange,
  clockTime,
  compareClockTime,
  containsTime,
  dayShift,
  formatClockRange,
  formatClockTime,
  fromMinutesSinceMidnight,
  minutesSinceMidnight,
  parseClockRange,
  parseClockTime,
  rangeLengthMinutes,
  rangesOverlap,
  wrapsMidnight,
} from '../../../../src/domain/time/clocktime.js';
import { ValidationError } from '../../../../src/domain/errors.js';

const t = parseClockTime;

describe('clock time', () => {
  it('builds a time from parts', () => {
    expect(clockTime(6, 45)).toEqual({ hour: 6, minute: 45 });
  });

  it('rejects an hour or minute out of range', () => {
    expect(() => clockTime(24, 0)).toThrow(/between 0 and 23/);
    expect(() => clockTime(6, 60)).toThrow(/between 0 and 59/);
  });

  it('rejects fractional parts', () => {
    expect(() => clockTime(6, 30.5)).toThrow(ValidationError);
  });

  it('parses and formats a padded time', () => {
    expect(formatClockTime(t('06:05'))).toBe('06:05');
    expect(formatClockTime(clockTime(23, 59))).toBe('23:59');
  });

  it('refuses a loose time string', () => {
    expect(() => t('6:05')).toThrow(/HH:MM/);
    expect(() => t('24:00')).toThrow(/HH:MM/);
    expect(() => t('06:60')).toThrow(/HH:MM/);
    expect(() => t('quarter past')).toThrow(/HH:MM/);
  });
});

describe('minutes since midnight', () => {
  it('counts from zero', () => {
    expect(minutesSinceMidnight(MIDNIGHT)).toBe(0);
    expect(minutesSinceMidnight(t('01:30'))).toBe(90);
    expect(minutesSinceMidnight(t('23:59'))).toBe(MINUTES_IN_DAY - 1);
  });

  it('round trips', () => {
    expect(fromMinutesSinceMidnight(90)).toEqual(t('01:30'));
    expect(fromMinutesSinceMidnight(0)).toEqual(MIDNIGHT);
  });

  it('wraps a value past the end of the day', () => {
    expect(fromMinutesSinceMidnight(MINUTES_IN_DAY + 15)).toEqual(t('00:15'));
    expect(fromMinutesSinceMidnight(-15)).toEqual(t('23:45'));
  });

  it('rejects a fractional minute count', () => {
    expect(() => fromMinutesSinceMidnight(1.5)).toThrow(/whole number/);
  });
});

describe('adding minutes', () => {
  it('moves within the day', () => {
    expect(addMinutes(t('06:00'), 90)).toEqual(t('07:30'));
  });

  it('wraps past midnight and reports the shift', () => {
    expect(addMinutes(t('23:30'), 60)).toEqual(t('00:30'));
    expect(dayShift(t('23:30'), 60)).toBe(1);
    expect(dayShift(t('06:00'), 90)).toBe(0);
    expect(dayShift(t('00:30'), -60)).toBe(-1);
  });
});

describe('comparison', () => {
  it('orders two times', () => {
    expect(compareClockTime(t('06:00'), t('07:00'))).toBe(-1);
    expect(compareClockTime(t('07:00'), t('06:00'))).toBe(1);
    expect(compareClockTime(t('07:00'), t('07:00'))).toBe(0);
  });
});

describe('ranges', () => {
  it('parses and formats', () => {
    expect(formatClockRange(parseClockRange('22:00-06:00'))).toBe('22:00-06:00');
  });

  it('refuses a malformed range', () => {
    expect(() => parseClockRange('22:00')).toThrow(/HH:MM-HH:MM/);
    expect(() => parseClockRange('22:00-06:00-07:00')).toThrow(/HH:MM-HH:MM/);
  });

  it('refuses an empty range', () => {
    expect(() => clockRange(t('06:00'), t('06:00'))).toThrow(/same minute/);
  });

  it('knows when it wraps past midnight', () => {
    expect(wrapsMidnight(parseClockRange('22:00-06:00'))).toBe(true);
    expect(wrapsMidnight(parseClockRange('06:00-22:00'))).toBe(false);
  });

  it('measures its length either way round', () => {
    expect(rangeLengthMinutes(parseClockRange('06:00-22:00'))).toBe(960);
    expect(rangeLengthMinutes(parseClockRange('22:00-06:00'))).toBe(480);
  });

  it('is closed at the start and open at the end', () => {
    const day = parseClockRange('06:00-22:00');
    expect(containsTime(day, t('06:00'))).toBe(true);
    expect(containsTime(day, t('21:59'))).toBe(true);
    expect(containsTime(day, t('22:00'))).toBe(false);
    expect(containsTime(day, t('05:59'))).toBe(false);
  });

  it('handles a range that wraps past midnight', () => {
    const night = parseClockRange('22:00-06:00');
    expect(containsTime(night, t('23:30'))).toBe(true);
    expect(containsTime(night, t('02:00'))).toBe(true);
    expect(containsTime(night, t('06:00'))).toBe(false);
    expect(containsTime(night, t('12:00'))).toBe(false);
  });

  it('detects overlap between two ranges', () => {
    expect(rangesOverlap(parseClockRange('22:00-06:00'), parseClockRange('05:00-09:00'))).toBe(
      true,
    );
    expect(rangesOverlap(parseClockRange('22:00-06:00'), parseClockRange('07:00-09:00'))).toBe(
      false,
    );
    expect(rangesOverlap(parseClockRange('06:00-22:00'), parseClockRange('06:00-22:00'))).toBe(
      true,
    );
  });
});
