import { ValidationError } from '../errors.js';

/**
 * A time on a wall clock, with no date and no zone. Quiet hours, muster times
 * and reminder windows are all quoted like this by the people who set them.
 */
export interface ClockTime {
  readonly hour: number;
  readonly minute: number;
}

export interface ClockRange {
  readonly from: ClockTime;
  /** Exclusive. A range may wrap past midnight, and most rest windows do. */
  readonly to: ClockTime;
}

const PATTERN = /^([01]\d|2[0-3]):([0-5]\d)$/;
export const MINUTES_IN_DAY = 24 * 60;

export function clockTime(hour: number, minute: number): ClockTime {
  if (!Number.isInteger(hour) || !Number.isInteger(minute)) {
    throw ValidationError.field('time', 'needs whole numbers for hour and minute');
  }
  if (hour < 0 || hour > 23) {
    throw ValidationError.field('hour', `must be between 0 and 23, got ${hour}`);
  }
  if (minute < 0 || minute > 59) {
    throw ValidationError.field('minute', `must be between 0 and 59, got ${minute}`);
  }
  return { hour, minute };
}

export function parseClockTime(value: string): ClockTime {
  const match = PATTERN.exec(value);
  if (match === null) {
    throw ValidationError.field('time', `must be written as HH:MM, got ${value}`);
  }
  return clockTime(Number(match[1]), Number(match[2]));
}

export function formatClockTime(time: ClockTime): string {
  return `${String(time.hour).padStart(2, '0')}:${String(time.minute).padStart(2, '0')}`;
}

export function minutesSinceMidnight(time: ClockTime): number {
  return time.hour * 60 + time.minute;
}

export function fromMinutesSinceMidnight(minutes: number): ClockTime {
  if (!Number.isInteger(minutes)) {
    throw ValidationError.field('minutes', 'must be a whole number');
  }
  const wrapped = ((minutes % MINUTES_IN_DAY) + MINUTES_IN_DAY) % MINUTES_IN_DAY;
  return clockTime(Math.floor(wrapped / 60), wrapped % 60);
}

/** Wraps at midnight. The caller finds out how far it wrapped from `dayShift`. */
export function addMinutes(time: ClockTime, minutes: number): ClockTime {
  return fromMinutesSinceMidnight(minutesSinceMidnight(time) + minutes);
}

export function dayShift(time: ClockTime, minutes: number): number {
  return Math.floor((minutesSinceMidnight(time) + minutes) / MINUTES_IN_DAY);
}

export function compareClockTime(left: ClockTime, right: ClockTime): number {
  const difference = minutesSinceMidnight(left) - minutesSinceMidnight(right);
  if (difference < 0) return -1;
  return difference > 0 ? 1 : 0;
}

export function clockRange(from: ClockTime, to: ClockTime): ClockRange {
  if (compareClockTime(from, to) === 0) {
    throw ValidationError.field('range', 'must not start and end at the same minute');
  }
  return { from, to };
}

export function parseClockRange(value: string): ClockRange {
  const parts = value.split('-');
  if (parts.length !== 2) {
    throw ValidationError.field('range', `must be written as HH:MM-HH:MM, got ${value}`);
  }
  return clockRange(parseClockTime(parts[0] ?? ''), parseClockTime(parts[1] ?? ''));
}

export function formatClockRange(range: ClockRange): string {
  return `${formatClockTime(range.from)}-${formatClockTime(range.to)}`;
}

export function wrapsMidnight(range: ClockRange): boolean {
  return compareClockTime(range.from, range.to) > 0;
}

export function rangeLengthMinutes(range: ClockRange): number {
  const from = minutesSinceMidnight(range.from);
  const to = minutesSinceMidnight(range.to);
  return to > from ? to - from : MINUTES_IN_DAY - from + to;
}

export function containsTime(range: ClockRange, time: ClockTime): boolean {
  const at = minutesSinceMidnight(time);
  const from = minutesSinceMidnight(range.from);
  const to = minutesSinceMidnight(range.to);
  if (wrapsMidnight(range)) {
    return at >= from || at < to;
  }
  return at >= from && at < to;
}

export function rangesOverlap(left: ClockRange, right: ClockRange): boolean {
  return (
    containsTime(left, right.from) ||
    containsTime(right, left.from) ||
    formatClockRange(left) === formatClockRange(right)
  );
}

export const MIDNIGHT: ClockTime = { hour: 0, minute: 0 };
