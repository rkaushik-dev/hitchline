import type { Habit } from '../habit/habit.js';
import { meetsTarget, sumReadings, targetProgress } from '../habit/target.js';
import { countInRange, readingsInRange, type ResolvedCheckIn } from '../checkin/resolve.js';
import type { Obligation } from '../schedule/obligation.js';
import { isAfter, type CivilDate } from '../time/civil.js';

/**
 * How an obligation turned out. `pending` is kept apart from `missed` because a
 * hitch that is still running has not been failed yet, and a report that says
 * otherwise is the fastest way to lose a crew's trust in the tool.
 */
export type AdherenceStatus = 'met' | 'partial' | 'missed' | 'pending';

export interface ObligationResult {
  readonly obligation: Obligation;
  readonly count: number;
  /** Sum of the readings inside the window, undefined when the habit has no target. */
  readonly total: number | undefined;
  readonly status: AdherenceStatus;
  /** Zero to one, counting both the number of check ins and the target. */
  readonly progress: number;
}

export interface Evaluation {
  readonly habit: Habit;
  readonly obligation: Obligation;
  readonly resolved: readonly ResolvedCheckIn[];
  readonly asOf: CivilDate;
}

function countProgress(count: number, times: number): number {
  if (times <= 0) return 1;
  return Math.min(1, count / times);
}

export function evaluateObligation(request: Evaluation): ObligationResult {
  const { obligation, habit, resolved, asOf } = request;
  const mine = resolved.filter((item) => item.checkIn.habitId === habit.id);
  const count = countInRange(mine, obligation.window);
  const target = habit.target;

  const total =
    target === undefined
      ? undefined
      : sumReadings(target.unit, readingsInRange(mine, obligation.window));

  const enoughTimes = count >= obligation.times;
  const enoughAmount =
    target === undefined || total === undefined ? true : meetsTarget(target, total);
  const met = enoughTimes && enoughAmount;

  const progress =
    target === undefined || total === undefined
      ? countProgress(count, obligation.times)
      : Math.min(countProgress(count, obligation.times), targetProgress(target, total));

  const closed = isAfter(asOf, obligation.window.to);
  const status: AdherenceStatus = met
    ? 'met'
    : !closed
      ? 'pending'
      : count === 0
        ? 'missed'
        : 'partial';

  return { obligation, count, total, status, progress };
}

export function evaluateAll(
  habit: Habit,
  obligations: readonly Obligation[],
  resolved: readonly ResolvedCheckIn[],
  asOf: CivilDate,
): ObligationResult[] {
  return obligations.map((obligation) => evaluateObligation({ habit, obligation, resolved, asOf }));
}

export function isSettled(result: ObligationResult): boolean {
  return result.status !== 'pending';
}

export function countByStatus(
  results: readonly ObligationResult[],
): Record<AdherenceStatus, number> {
  const tally: Record<AdherenceStatus, number> = { met: 0, partial: 0, missed: 0, pending: 0 };
  for (const result of results) {
    tally[result.status] += 1;
  }
  return tally;
}

/**
 * Share of the settled obligations that were met. Pending ones are left out of
 * both halves of the fraction rather than counted as failures.
 */
export function adherenceRate(results: readonly ObligationResult[]): number {
  const settled = results.filter(isSettled);
  if (settled.length === 0) return 1;
  const met = settled.filter((result) => result.status === 'met').length;
  return met / settled.length;
}
