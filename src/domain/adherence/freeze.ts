import { ValidationError } from '../errors.js';
import { compareCivil, daysBetween, formatCivilDate, type CivilDate } from '../time/civil.js';
import { isSettled, type ObligationResult } from './match.js';
import { isShielded, type Shield } from './shield.js';
import type { CrewMemberId } from '../ids.js';
import type { StreakSummary } from './streak.js';

/**
 * Freezes. A shield is granted for a reason somebody signs off; a freeze is
 * spent by the person themselves and there are only so many. The difference
 * matters: a shield says nothing was owed, a freeze says something was owed
 * and missed but the run survives it.
 */
export interface FreezeBudget {
  /** How many misses a run may absorb inside the window. */
  readonly allowance: number;
  /** Days the allowance is counted over, ending on the day being scored. */
  readonly windowDays: number;
}

export const DEFAULT_FREEZE: FreezeBudget = { allowance: 1, windowDays: 28 };

export function freezeBudget(input: Partial<FreezeBudget> = {}): FreezeBudget {
  const budget = { ...DEFAULT_FREEZE, ...input };
  if (!Number.isInteger(budget.allowance) || budget.allowance < 0 || budget.allowance > 10) {
    throw ValidationError.field('allowance', 'must be a whole number from 0 to 10');
  }
  if (!Number.isInteger(budget.windowDays) || budget.windowDays < 1 || budget.windowDays > 365) {
    throw ValidationError.field('windowDays', 'must be a whole number of days from 1 to 365');
  }
  return budget;
}

export interface FrozenStreak extends StreakSummary {
  /** Misses the run absorbed rather than breaking on. */
  readonly frozen: number;
  readonly freezesLeft: number;
}

export interface FreezeRequest {
  readonly results: readonly ObligationResult[];
  readonly shields: readonly Shield[];
  readonly memberId: CrewMemberId;
  readonly budget?: FreezeBudget;
}

/**
 * Walks the run the way `computeStreak` does, but lets a miss be absorbed
 * while the budget holds. Freezes spent outside the window come back, so a
 * person who slipped once in March is not still paying for it in June.
 */
export function computeFrozenStreak(request: FreezeRequest): FrozenStreak {
  const budget = request.budget ?? DEFAULT_FREEZE;
  const settled = request.results
    .filter(isSettled)
    .sort((left, right) => compareCivil(left.obligation.dueOn, right.obligation.dueOn));

  let current = 0;
  let longest = 0;
  let shielded = 0;
  let frozen = 0;
  let lastMetOn: CivilDate | undefined;
  let brokenOn: CivilDate | undefined;
  const spent: CivilDate[] = [];

  for (const result of settled) {
    const due = result.obligation.dueOn;
    if (isShielded(request.shields, request.memberId, due)) {
      shielded += 1;
      continue;
    }
    if (result.status === 'met') {
      current += 1;
      longest = Math.max(longest, current);
      lastMetOn = due;
      continue;
    }

    const live = spent.filter((day) => daysBetween(day, due) < budget.windowDays);
    if (current > 0 && live.length < budget.allowance) {
      live.push(due);
      spent.length = 0;
      spent.push(...live);
      frozen += 1;
      continue;
    }
    current = 0;
    brokenOn = due;
  }

  const last = settled[settled.length - 1]?.obligation.dueOn;
  const stillLive =
    last === undefined
      ? spent.length
      : spent.filter((day) => daysBetween(day, last) < budget.windowDays).length;

  return {
    current,
    longest,
    shielded,
    frozen,
    lastMetOn,
    brokenOn,
    freezesLeft: Math.max(0, budget.allowance - stillLive),
  };
}

export function describeFreezes(summary: FrozenStreak): string {
  if (summary.frozen === 0) return `${String(summary.freezesLeft)} freezes in hand`;
  const used = summary.frozen === 1 ? 'one day' : `${String(summary.frozen)} days`;
  return `${used} carried, ${String(summary.freezesLeft)} freezes in hand`;
}

export function lastMetLabel(summary: FrozenStreak): string {
  return summary.lastMetOn === undefined ? 'never' : formatCivilDate(summary.lastMetOn);
}
