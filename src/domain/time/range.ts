import { ValidationError } from '../errors.js';
import {
  addDays,
  compareCivil,
  dayCount,
  eachDay,
  formatCivilDate,
  isAfter,
  isBefore,
  maxDate,
  minDate,
  parseCivilDate,
  type CivilDate,
} from './civil.js';

/**
 * A run of whole days, closed at both ends. Hitches, leave and quota windows
 * are all quoted inclusively by the people who work them, so the type is too.
 */
export interface DateRange {
  readonly from: CivilDate;
  readonly to: CivilDate;
}

export function dateRange(from: CivilDate, to: CivilDate): DateRange {
  if (isAfter(from, to)) {
    throw ValidationError.field(
      'range',
      `ends on ${formatCivilDate(to)} before it starts on ${formatCivilDate(from)}`,
    );
  }
  return { from, to };
}

export function singleDay(date: CivilDate): DateRange {
  return { from: date, to: date };
}

export function parseDateRange(value: string): DateRange {
  const parts = value.split('..');
  if (parts.length !== 2) {
    throw ValidationError.field('range', `must be written as YYYY-MM-DD..YYYY-MM-DD, got ${value}`);
  }
  return dateRange(parseCivilDate(parts[0] ?? ''), parseCivilDate(parts[1] ?? ''));
}

export function formatDateRange(range: DateRange): string {
  return `${formatCivilDate(range.from)}..${formatCivilDate(range.to)}`;
}

export function rangeLength(range: DateRange): number {
  return dayCount(range.from, range.to);
}

export function rangeContains(range: DateRange, date: CivilDate): boolean {
  return !isBefore(date, range.from) && !isAfter(date, range.to);
}

export function rangeCovers(outer: DateRange, inner: DateRange): boolean {
  return rangeContains(outer, inner.from) && rangeContains(outer, inner.to);
}

export function rangesTouch(left: DateRange, right: DateRange): boolean {
  return !isAfter(left.from, addDays(right.to, 1)) && !isAfter(right.from, addDays(left.to, 1));
}

export function rangesIntersect(left: DateRange, right: DateRange): boolean {
  return !isAfter(left.from, right.to) && !isAfter(right.from, left.to);
}

export function intersectRanges(left: DateRange, right: DateRange): DateRange | undefined {
  if (!rangesIntersect(left, right)) return undefined;
  return { from: maxDate(left.from, right.from), to: minDate(left.to, right.to) };
}

/** Cuts a range down to what fits inside `bounds`, or nothing when it misses. */
export function clampRange(range: DateRange, bounds: DateRange): DateRange | undefined {
  return intersectRanges(range, bounds);
}

export function shiftRange(range: DateRange, days: number): DateRange {
  return { from: addDays(range.from, days), to: addDays(range.to, days) };
}

export function daysOf(range: DateRange): CivilDate[] {
  return [...eachDay(range.from, range.to)];
}

export function compareRanges(left: DateRange, right: DateRange): number {
  const byStart = compareCivil(left.from, right.from);
  return byStart === 0 ? compareCivil(left.to, right.to) : byStart;
}

/**
 * Sorts and merges. Two ranges that only touch at the join are merged, because
 * a fortnight of leave entered as two weeks is still one absence.
 */
export function mergeRanges(ranges: readonly DateRange[]): DateRange[] {
  const sorted = [...ranges].sort(compareRanges);
  const merged: DateRange[] = [];
  for (const range of sorted) {
    const last = merged[merged.length - 1];
    if (last !== undefined && rangesTouch(last, range)) {
      merged[merged.length - 1] = { from: last.from, to: maxDate(last.to, range.to) };
      continue;
    }
    merged.push(range);
  }
  return merged;
}

/** Everything in `range` that no entry of `holes` covers. */
export function subtractRanges(range: DateRange, holes: readonly DateRange[]): DateRange[] {
  let remaining: DateRange[] = [range];
  for (const hole of mergeRanges(holes)) {
    const next: DateRange[] = [];
    for (const piece of remaining) {
      const overlap = intersectRanges(piece, hole);
      if (overlap === undefined) {
        next.push(piece);
        continue;
      }
      if (isBefore(piece.from, overlap.from)) {
        next.push({ from: piece.from, to: addDays(overlap.from, -1) });
      }
      if (isAfter(piece.to, overlap.to)) {
        next.push({ from: addDays(overlap.to, 1), to: piece.to });
      }
    }
    remaining = next;
  }
  return remaining;
}

/** Splits into consecutive chunks of `size` days, the last one possibly short. */
export function chunkRange(range: DateRange, size: number): DateRange[] {
  if (!Number.isInteger(size) || size < 1) {
    throw ValidationError.field('size', `must be a whole number of days above zero, got ${size}`);
  }
  const chunks: DateRange[] = [];
  let cursor = range.from;
  while (!isAfter(cursor, range.to)) {
    const end = minDate(addDays(cursor, size - 1), range.to);
    chunks.push({ from: cursor, to: end });
    cursor = addDays(end, 1);
  }
  return chunks;
}
