import type { HabitId } from '../ids.js';
import { hitchNumberOn, hitchRange } from '../rotation/assignment.js';
import type { Roster } from '../rotation/roster.js';
import { formatDateRange, rangeContains, type DateRange } from '../time/range.js';
import { adherenceRate, countByStatus, isSettled, type ObligationResult } from './match.js';
import { shieldedDayCount, type Shield } from './shield.js';

/**
 * What a window looked like once every obligation in it was scored. The counts
 * are kept beside the rate because a rate on its own hides how much was owed,
 * and one missed day out of two reads the same as fifty out of a hundred.
 */
export interface PeriodSummary {
  readonly range: DateRange;
  readonly owed: number;
  readonly met: number;
  readonly partial: number;
  readonly missed: number;
  readonly pending: number;
  readonly shieldedDays: number;
  readonly rate: number;
}

export interface HabitSummary extends PeriodSummary {
  readonly habitId: HabitId;
}

export interface SummaryRequest {
  readonly roster: Roster;
  readonly results: readonly ObligationResult[];
  readonly shields: readonly Shield[];
  readonly range: DateRange;
}

function inRange(results: readonly ObligationResult[], range: DateRange): ObligationResult[] {
  return results.filter((result) => rangeContains(range, result.obligation.dueOn));
}

export function summarise(request: SummaryRequest): PeriodSummary {
  const scoped = inRange(request.results, request.range);
  const counts = countByStatus(scoped);
  return {
    range: request.range,
    owed: scoped.length,
    met: counts.met,
    partial: counts.partial,
    missed: counts.missed,
    pending: counts.pending,
    shieldedDays: shieldedDayCount(request.shields, request.roster.member.id, request.range),
    rate: adherenceRate(scoped),
  };
}

export function summariseByHabit(request: SummaryRequest): HabitSummary[] {
  const scoped = inRange(request.results, request.range);
  const ids = [...new Set(scoped.map((result) => result.obligation.habitId))].sort();
  return ids.map((habitId) => ({
    habitId,
    ...summarise({ ...request, results: scoped.filter((r) => r.obligation.habitId === habitId) }),
  }));
}

/**
 * One summary per turn of the rotation the window touches. A crew talks about
 * their last hitch, not their last month, so this is the shape most reports
 * are built on.
 */
export function summariseByHitch(request: SummaryRequest): PeriodSummary[] {
  const { rotation } = request.roster.member;
  const first = hitchNumberOn(rotation, request.range.from);
  const last = hitchNumberOn(rotation, request.range.to);
  const summaries: PeriodSummary[] = [];
  for (let number = first; number <= last; number += 1) {
    summaries.push(summarise({ ...request, range: hitchRange(rotation, number) }));
  }
  return summaries;
}

export function isClean(summary: PeriodSummary): boolean {
  return summary.missed === 0 && summary.partial === 0;
}

export function settledCount(results: readonly ObligationResult[]): number {
  return results.filter(isSettled).length;
}

export function describeSummary(summary: PeriodSummary): string {
  const percent = Math.round(summary.rate * 100);
  return `${formatDateRange(summary.range)} ${summary.met} of ${summary.owed} at ${percent}%`;
}
