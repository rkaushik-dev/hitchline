import type { CrewMember } from '../crew/member.js';
import { rotationDayAt, type RotationDay } from '../rotation/zoning.js';
import { formatCivilDate, type CivilDate } from '../time/civil.js';
import { rangeContains, type DateRange } from '../time/range.js';
import type { CheckIn } from './checkin.js';

/**
 * A check in placed on the rotation day it belongs to. Everything that counts
 * check ins works on these rather than on raw instants, because two entries an
 * hour apart can belong to different days and two a day apart to the same one.
 */
export interface ResolvedCheckIn {
  readonly checkIn: CheckIn;
  readonly day: RotationDay;
}

export function resolveCheckIn(member: CrewMember, entry: CheckIn): ResolvedCheckIn {
  return { checkIn: entry, day: rotationDayAt(member, entry.at) };
}

export function resolveAll(member: CrewMember, entries: readonly CheckIn[]): ResolvedCheckIn[] {
  return entries.map((entry) => resolveCheckIn(member, entry));
}

export function onDate(resolved: readonly ResolvedCheckIn[], date: CivilDate): ResolvedCheckIn[] {
  const wanted = formatCivilDate(date);
  return resolved.filter((item) => formatCivilDate(item.day.date) === wanted);
}

export function inRange(resolved: readonly ResolvedCheckIn[], range: DateRange): ResolvedCheckIn[] {
  return resolved.filter((item) => rangeContains(range, item.day.date));
}

/** Keyed by the iso date, so a caller can read a whole month in one pass. */
export function groupByDay(resolved: readonly ResolvedCheckIn[]): Map<string, ResolvedCheckIn[]> {
  const groups = new Map<string, ResolvedCheckIn[]>();
  for (const item of resolved) {
    const key = formatCivilDate(item.day.date);
    const bucket = groups.get(key);
    if (bucket === undefined) {
      groups.set(key, [item]);
      continue;
    }
    bucket.push(item);
  }
  return groups;
}

export function countInRange(resolved: readonly ResolvedCheckIn[], range: DateRange): number {
  return inRange(resolved, range).length;
}

/** The readings inside a window, with entries that carried none left out. */
export function readingsInRange(resolved: readonly ResolvedCheckIn[], range: DateRange): number[] {
  const readings: number[] = [];
  for (const item of inRange(resolved, range)) {
    if (item.checkIn.reading !== undefined) readings.push(item.checkIn.reading);
  }
  return readings;
}

/** Entries whose two clocks disagreed about the date, worth showing in a report. */
export function straddling(resolved: readonly ResolvedCheckIn[]): ResolvedCheckIn[] {
  return resolved.filter((item) => item.day.straddled);
}

export function daysTouched(resolved: readonly ResolvedCheckIn[]): string[] {
  return [...groupByDay(resolved).keys()].sort();
}
