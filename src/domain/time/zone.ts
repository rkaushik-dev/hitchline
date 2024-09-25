import { ValidationError } from '../errors.js';
import { civilDate, formatCivilDate, type CivilDate } from './civil.js';
import { clockTime, type ClockTime } from './clocktime.js';

/**
 * Zone handling sits on the platform's own tz database through `Intl`. We keep
 * the arithmetic here rather than in the modules that need it, because a crew
 * member changes zone mid rotation and every reading of "what day is it for
 * them" has to agree.
 */
export type ZoneId = string;

export interface Instant {
  readonly epochMs: number;
}

/**
 * What happened when a wall clock time was pushed back onto the timeline.
 * `skipped` is a spring forward gap, `ambiguous` an autumn overlap.
 */
export type ZonedOutcome = 'exact' | 'skipped' | 'ambiguous';

export interface ZonedResolution {
  readonly instant: Instant;
  readonly outcome: ZonedOutcome;
  readonly offsetMinutes: number;
}

const MINUTE_MS = 60_000;
const formatters = new Map<ZoneId, Intl.DateTimeFormat>();

function formatterFor(zone: ZoneId): Intl.DateTimeFormat {
  const cached = formatters.get(zone);
  if (cached !== undefined) return cached;
  let created: Intl.DateTimeFormat;
  try {
    created = new Intl.DateTimeFormat('en-GB', {
      timeZone: zone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  } catch {
    throw ValidationError.field('zone', `is not a timezone this platform knows: ${zone}`);
  }
  formatters.set(zone, created);
  return created;
}

export function isKnownZone(zone: string): boolean {
  try {
    formatterFor(zone);
    return true;
  } catch {
    return false;
  }
}

export function zoneId(raw: string): ZoneId {
  formatterFor(raw);
  return raw;
}

export function instant(epochMs: number): Instant {
  if (!Number.isFinite(epochMs)) {
    throw ValidationError.field('instant', 'must be a finite number of milliseconds');
  }
  return { epochMs: Math.trunc(epochMs) };
}

export function parseInstant(value: string): Instant {
  const parsed = Date.parse(value);
  if (Number.isNaN(parsed)) {
    throw ValidationError.field('instant', `must be an iso timestamp, got ${value}`);
  }
  return { epochMs: parsed };
}

export function formatInstant(at: Instant): string {
  return new Date(at.epochMs).toISOString();
}

interface WallParts {
  readonly year: number;
  readonly month: number;
  readonly day: number;
  readonly hour: number;
  readonly minute: number;
  readonly second: number;
}

function wallPartsIn(zone: ZoneId, at: Instant): WallParts {
  const parts = formatterFor(zone).formatToParts(new Date(at.epochMs));
  const read = (type: Intl.DateTimeFormatPartTypes): number => {
    const found = parts.find((part) => part.type === type);
    if (found === undefined) {
      throw ValidationError.field('zone', `gave no ${type} for ${formatInstant(at)}`);
    }
    return Number(found.value);
  };
  return {
    year: read('year'),
    month: read('month'),
    day: read('day'),
    hour: read('hour') % 24,
    minute: read('minute'),
    second: read('second'),
  };
}

/** Minutes east of UTC at that instant, so Oslo in summer is 120. */
export function offsetMinutesAt(zone: ZoneId, at: Instant): number {
  const wall = wallPartsIn(zone, at);
  const asUtc = Date.UTC(wall.year, wall.month - 1, wall.day, wall.hour, wall.minute, wall.second);
  return Math.round((asUtc - Math.floor(at.epochMs / 1000) * 1000) / MINUTE_MS);
}

export function civilDateIn(zone: ZoneId, at: Instant): CivilDate {
  const wall = wallPartsIn(zone, at);
  return civilDate(wall.year, wall.month, wall.day);
}

export function clockTimeIn(zone: ZoneId, at: Instant): ClockTime {
  const wall = wallPartsIn(zone, at);
  return clockTime(wall.hour, wall.minute);
}

/**
 * Pushes a wall clock reading back onto the timeline. A time inside a spring
 * forward gap is reported as `skipped` and answered with the first instant
 * after the gap, which is what a person tapping the button at that moment
 * actually means.
 */
export function resolveZoned(zone: ZoneId, date: CivilDate, time: ClockTime): ZonedResolution {
  const asUtc = Date.UTC(date.year, date.month - 1, date.day, time.hour, time.minute, 0);
  const dayMs = 24 * 60 * MINUTE_MS;
  const before = offsetMinutesAt(zone, instant(asUtc - dayMs));
  const after = offsetMinutesAt(zone, instant(asUtc + dayMs));

  const withBefore = asUtc - before * MINUTE_MS;
  const withAfter = asUtc - after * MINUTE_MS;
  const holds = (candidate: number): boolean =>
    offsetMinutesAt(zone, instant(candidate)) * MINUTE_MS === asUtc - candidate;

  const valid = [withBefore, withAfter].filter((candidate, index, all) => {
    if (index > 0 && all[0] === candidate) return false;
    return holds(candidate);
  });

  if (valid.length === 1) {
    const only = valid[0] ?? withBefore;
    return {
      instant: instant(only),
      outcome: 'exact',
      offsetMinutes: offsetMinutesAt(zone, instant(only)),
    };
  }
  if (valid.length > 1) {
    const earliest = Math.min(...valid);
    return {
      instant: instant(earliest),
      outcome: 'ambiguous',
      offsetMinutes: offsetMinutesAt(zone, instant(earliest)),
    };
  }
  // Nothing held, so the wall time falls inside a spring forward gap. Reading it
  // with the offset in force before the change slides it past the gap, which is
  // where a person pressing the button at that minute has actually ended up.
  return {
    instant: instant(withBefore),
    outcome: 'skipped',
    offsetMinutes: offsetMinutesAt(zone, instant(withBefore)),
  };
}

export function zonedInstant(zone: ZoneId, date: CivilDate, time: ClockTime): Instant {
  return resolveZoned(zone, date, time).instant;
}

export function startOfDayIn(zone: ZoneId, date: CivilDate): Instant {
  return zonedInstant(zone, date, { hour: 0, minute: 0 });
}

export function describeZoned(zone: ZoneId, at: Instant): string {
  const offset = offsetMinutesAt(zone, at);
  const sign = offset < 0 ? '-' : '+';
  const absolute = Math.abs(offset);
  const hours = String(Math.floor(absolute / 60)).padStart(2, '0');
  const minutes = String(absolute % 60).padStart(2, '0');
  const date = formatCivilDate(civilDateIn(zone, at));
  const wall = clockTimeIn(zone, at);
  const clock = `${String(wall.hour).padStart(2, '0')}:${String(wall.minute).padStart(2, '0')}`;
  return `${date} ${clock}${sign}${hours}:${minutes}`;
}
