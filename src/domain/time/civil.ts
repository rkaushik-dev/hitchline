import { ValidationError } from '../errors.js';

/**
 * A date with no zone attached. Rotations are planned on these: a hitch starts
 * on a date, not at an instant, and the instant it starts at depends on where
 * the crew member happens to be standing.
 */
export interface CivilDate {
  readonly year: number;
  readonly month: number;
  readonly day: number;
}

export type Weekday = 1 | 2 | 3 | 4 | 5 | 6 | 7;

const ISO_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;
const DAYS_IN_MONTH = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
/** 1970-01-01 was a Thursday, which is weekday 4 in the ISO numbering. */
const EPOCH_WEEKDAY = 4;

export function isLeapYear(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

export function daysInMonth(year: number, month: number): number {
  if (month < 1 || month > 12) {
    throw ValidationError.field('month', `must be between 1 and 12, got ${month}`);
  }
  if (month === 2 && isLeapYear(year)) return 29;
  return DAYS_IN_MONTH[month - 1] ?? 30;
}

export function civilDate(year: number, month: number, day: number): CivilDate {
  if (!Number.isInteger(year) || !Number.isInteger(month) || !Number.isInteger(day)) {
    throw ValidationError.field('date', 'needs whole numbers for year, month and day');
  }
  if (year < 1900 || year > 2999) {
    throw ValidationError.field('year', `must be between 1900 and 2999, got ${year}`);
  }
  const limit = daysInMonth(year, month);
  if (day < 1 || day > limit) {
    throw ValidationError.field('day', `must be between 1 and ${limit} for that month, got ${day}`);
  }
  return { year, month, day };
}

export function parseCivilDate(value: string): CivilDate {
  const match = ISO_PATTERN.exec(value);
  if (match === null) {
    throw ValidationError.field('date', `must be written as YYYY-MM-DD, got ${value}`);
  }
  return civilDate(Number(match[1]), Number(match[2]), Number(match[3]));
}

export function formatCivilDate(date: CivilDate): string {
  const month = String(date.month).padStart(2, '0');
  const day = String(date.day).padStart(2, '0');
  return `${date.year}-${month}-${day}`;
}

/** Days since 1970-01-01, negative before it. */
export function toEpochDay(date: CivilDate): number {
  const utc = Date.UTC(date.year, date.month - 1, date.day);
  return Math.floor(utc / 86_400_000);
}

export function fromEpochDay(epochDay: number): CivilDate {
  if (!Number.isInteger(epochDay)) {
    throw ValidationError.field('epochDay', 'must be a whole number of days');
  }
  const moment = new Date(epochDay * 86_400_000);
  return civilDate(moment.getUTCFullYear(), moment.getUTCMonth() + 1, moment.getUTCDate());
}

export function addDays(date: CivilDate, days: number): CivilDate {
  return fromEpochDay(toEpochDay(date) + days);
}

export function addMonths(date: CivilDate, months: number): CivilDate {
  const zeroBased = date.year * 12 + (date.month - 1) + months;
  const year = Math.floor(zeroBased / 12);
  const month = (zeroBased % 12) + 1;
  const day = Math.min(date.day, daysInMonth(year, month));
  return civilDate(year, month, day);
}

export function daysBetween(from: CivilDate, to: CivilDate): number {
  return toEpochDay(to) - toEpochDay(from);
}

export function compareCivil(left: CivilDate, right: CivilDate): number {
  const difference = toEpochDay(left) - toEpochDay(right);
  if (difference < 0) return -1;
  return difference > 0 ? 1 : 0;
}

export function isSameDay(left: CivilDate, right: CivilDate): boolean {
  return compareCivil(left, right) === 0;
}

export function isBefore(left: CivilDate, right: CivilDate): boolean {
  return compareCivil(left, right) < 0;
}

export function isAfter(left: CivilDate, right: CivilDate): boolean {
  return compareCivil(left, right) > 0;
}

/** Inclusive on both ends, because rosters are quoted that way. */
export function isWithin(date: CivilDate, from: CivilDate, to: CivilDate): boolean {
  return !isBefore(date, from) && !isAfter(date, to);
}

export function minDate(left: CivilDate, right: CivilDate): CivilDate {
  return isBefore(left, right) ? left : right;
}

export function maxDate(left: CivilDate, right: CivilDate): CivilDate {
  return isAfter(left, right) ? left : right;
}

export function dayOfWeek(date: CivilDate): Weekday {
  const shifted = (toEpochDay(date) + EPOCH_WEEKDAY - 1) % 7;
  const normalised = shifted < 0 ? shifted + 7 : shifted;
  return (normalised + 1) as Weekday;
}

export function isWeekend(date: CivilDate): boolean {
  return dayOfWeek(date) >= 6;
}

export function startOfIsoWeek(date: CivilDate): CivilDate {
  return addDays(date, 1 - dayOfWeek(date));
}

export function endOfIsoWeek(date: CivilDate): CivilDate {
  return addDays(startOfIsoWeek(date), 6);
}

export function startOfMonth(date: CivilDate): CivilDate {
  return civilDate(date.year, date.month, 1);
}

export function endOfMonth(date: CivilDate): CivilDate {
  return civilDate(date.year, date.month, daysInMonth(date.year, date.month));
}

/** Walks from `from` to `to` inclusive. Yields nothing when the pair is reversed. */
export function* eachDay(from: CivilDate, to: CivilDate): Generator<CivilDate> {
  let cursor = toEpochDay(from);
  const last = toEpochDay(to);
  while (cursor <= last) {
    yield fromEpochDay(cursor);
    cursor += 1;
  }
}

export function dayCount(from: CivilDate, to: CivilDate): number {
  return Math.max(0, daysBetween(from, to) + 1);
}
