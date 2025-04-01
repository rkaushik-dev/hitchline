import { ValidationError } from '../errors.js';
import { addDays, compareCivil, formatCivilDate, type CivilDate } from '../time/civil.js';
import { dateRange, rangeContains, type DateRange } from '../time/range.js';
import type { ObligationResult } from './match.js';

/**
 * A rule about how much slipping is tolerated. A streak says how good the last
 * run was; a quota says whether somebody is in trouble, which is a different
 * question and the one a supervisor is actually asked.
 */
export interface Quota {
  readonly name: string;
  /** How many misses are allowed inside the window before the rule bites. */
  readonly allowed: number;
  readonly windowDays: number;
  /** Whether a partial counts against the quota, or only an outright miss. */
  readonly countPartial: boolean;
}

export interface QuotaBreach {
  readonly quota: Quota;
  readonly window: DateRange;
  readonly counted: number;
  readonly firstOn: CivilDate;
}

export interface QuotaReading {
  readonly quota: Quota;
  readonly worst: number;
  readonly breaches: readonly QuotaBreach[];
}

const MAX_WINDOW_DAYS = 365;

export function quota(
  name: string,
  allowed: number,
  windowDays: number,
  countPartial = true,
): Quota {
  if (name.trim().length === 0) {
    throw ValidationError.field('name', 'must not be empty');
  }
  if (!Number.isInteger(allowed) || allowed < 0) {
    throw ValidationError.field('allowed', `must be zero or more, got ${allowed}`);
  }
  if (!Number.isInteger(windowDays) || windowDays < 1 || windowDays > MAX_WINDOW_DAYS) {
    throw ValidationError.field('windowDays', `must be between 1 and ${MAX_WINDOW_DAYS}`);
  }
  return { name: name.trim(), allowed, windowDays, countPartial };
}

function countsAgainst(quota: Quota, result: ObligationResult): boolean {
  if (result.status === 'missed') return true;
  return quota.countPartial && result.status === 'partial';
}

/**
 * Slides the window over every day something fell due and reports each stretch
 * where too much was dropped. Windows are anchored on the days themselves, so
 * the reading does not depend on where a caller happened to start looking.
 */
export function readQuota(quota: Quota, results: readonly ObligationResult[]): QuotaReading {
  const failures = results
    .filter((result) => countsAgainst(quota, result))
    .map((result) => result.obligation.dueOn)
    .sort(compareCivil);

  const breaches: QuotaBreach[] = [];
  let worst = 0;

  for (const [index, start] of failures.entries()) {
    const window = dateRange(start, addDays(start, quota.windowDays - 1));
    const inside = failures.filter((day) => rangeContains(window, day));
    worst = Math.max(worst, inside.length);
    if (inside.length > quota.allowed) {
      const overlapsLast = breaches.at(-1);
      if (overlapsLast !== undefined && rangeContains(overlapsLast.window, start)) {
        // Already reported as part of the stretch before this one.
        continue;
      }
      breaches.push({ quota, window, counted: inside.length, firstOn: failures[index] ?? start });
    }
  }

  return { quota, worst, breaches };
}

export function isBreached(reading: QuotaReading): boolean {
  return reading.breaches.length > 0;
}

/** How much room is left before the rule bites, given the worst stretch seen. */
export function headroom(reading: QuotaReading): number {
  return Math.max(0, reading.quota.allowed - reading.worst);
}

export function describeQuota(quota: Quota): string {
  const what = quota.countPartial ? 'misses or partials' : 'misses';
  return `${quota.name}: at most ${String(quota.allowed)} ${what} in ${String(quota.windowDays)} days`;
}

export function describeBreach(breach: QuotaBreach): string {
  const from = formatCivilDate(breach.window.from);
  const to = formatCivilDate(breach.window.to);
  return `${breach.quota.name} broken ${from} to ${to} with ${String(breach.counted)}`;
}
