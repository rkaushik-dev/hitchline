import type { ResolvedCheckIn } from '../checkin/resolve.js';
import { clockTimeIn } from '../time/zone.js';
import { minutesSinceMidnight } from '../time/clocktime.js';
import { DAY_ROLES, type DayRole } from '../rotation/pattern.js';
import type { ObligationResult } from './match.js';
import { adherenceRate } from './match.js';
import type { PeriodSummary } from './summary.js';

/**
 * The patterns a crew argues about. None of this changes what anybody is
 * measured against; it is here because "you only ever do it on the way to
 * breakfast" is a more useful thing to tell somebody than a percentage.
 */
export interface HourBucket {
  readonly hour: number;
  readonly count: number;
}

/** Counts check ins by the local hour they happened in, on the day's own clock. */
export function byHourOfDay(resolved: readonly ResolvedCheckIn[]): HourBucket[] {
  const counts = new Array<number>(24).fill(0);
  for (const item of resolved) {
    const local = clockTimeIn(item.day.zone, item.checkIn.at);
    const hour = Math.floor(minutesSinceMidnight(local) / 60);
    counts[hour] = (counts[hour] ?? 0) + 1;
  }
  return counts.map((count, hour) => ({ hour, count }));
}

export function busiestHour(resolved: readonly ResolvedCheckIn[]): HourBucket | undefined {
  const buckets = byHourOfDay(resolved).filter((bucket) => bucket.count > 0);
  if (buckets.length === 0) return undefined;
  return buckets.reduce((best, bucket) => (bucket.count > best.count ? bucket : best));
}

export interface RoleSplit {
  readonly role: DayRole;
  readonly owed: number;
  readonly met: number;
  readonly rate: number;
}

/**
 * How the same person does on shift against at home. This is the reading the
 * whole tool exists for: a habit that holds up offshore and collapses ashore
 * is a different problem from one nobody ever does.
 */
export function byRole(
  results: readonly ObligationResult[],
  roleOf: (result: ObligationResult) => DayRole,
): RoleSplit[] {
  const splits: RoleSplit[] = [];
  for (const role of DAY_ROLES) {
    const mine = results.filter((result) => roleOf(result) === role);
    if (mine.length === 0) continue;
    splits.push({
      role,
      owed: mine.length,
      met: mine.filter((result) => result.status === 'met').length,
      rate: adherenceRate(mine),
    });
  }
  return splits;
}

export type Trend = 'rising' | 'steady' | 'falling' | 'unknown';

/**
 * Compares the last window with the ones before it. Two readings are not a
 * trend, so anything shorter than three answers `unknown` rather than
 * inventing a direction.
 */
export function trendOf(summaries: readonly PeriodSummary[], tolerance = 0.05): Trend {
  const scored = summaries.filter((summary) => summary.owed > 0);
  if (scored.length < 3) return 'unknown';
  const last = scored[scored.length - 1];
  const earlier = scored.slice(0, -1);
  if (last === undefined) return 'unknown';
  const average = earlier.reduce((total, summary) => total + summary.rate, 0) / earlier.length;
  if (last.rate > average + tolerance) return 'rising';
  if (last.rate < average - tolerance) return 'falling';
  return 'steady';
}

export function describeTrend(trend: Trend): string {
  switch (trend) {
    case 'rising':
      return 'better than the hitches before it';
    case 'falling':
      return 'worse than the hitches before it';
    case 'steady':
      return 'about the same as the hitches before it';
    case 'unknown':
      return 'not enough hitches to say';
  }
}
