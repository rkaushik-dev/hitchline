import { ValidationError } from '../errors.js';
import type { CrewMemberId, HabitId } from '../ids.js';
import { formatCivilDate, isWithin, type CivilDate } from '../time/civil.js';
import {
  compareRanges,
  formatDateRange,
  rangeContains,
  rangeLength,
  type DateRange,
} from '../time/range.js';

/**
 * What the cadence was measured over. A day scoped obligation is owed on one
 * date; a hitch scoped one is owed across the whole rotation and can be
 * satisfied on any day of it.
 */
export type ObligationScope = 'day' | 'block' | 'hitch' | 'window';

export interface Obligation {
  readonly habitId: HabitId;
  readonly memberId: CrewMemberId;
  readonly scope: ObligationScope;
  /** Days a check in may land on to count towards this obligation. */
  readonly window: DateRange;
  /** The day it is reported against, always the last day of the window. */
  readonly dueOn: CivilDate;
  readonly times: number;
}

export interface ObligationInput {
  readonly habitId: HabitId;
  readonly memberId: CrewMemberId;
  readonly scope: ObligationScope;
  readonly window: DateRange;
  readonly times: number;
  readonly graceDays?: number;
}

export function obligation(input: ObligationInput): Obligation {
  if (!Number.isInteger(input.times) || input.times < 1) {
    throw ValidationError.field('times', `must be a whole number above zero, got ${input.times}`);
  }
  return {
    habitId: input.habitId,
    memberId: input.memberId,
    scope: input.scope,
    window: input.window,
    dueOn: input.window.to,
    times: input.times,
  };
}

/**
 * Stable identity for an obligation. The scope is part of it because the same
 * habit can be rescheduled onto a different scope, and the two runs must not
 * be mistaken for one another when a report is rebuilt.
 */
export function obligationKey(entry: Obligation): string {
  return `${entry.habitId}:${entry.scope}:${formatCivilDate(entry.dueOn)}`;
}

export function acceptsCheckInOn(entry: Obligation, date: CivilDate): boolean {
  return rangeContains(entry.window, date);
}

/** How wide the window is, which is how much room there was to get it done. */
export function obligationSpan(entry: Obligation): number {
  return rangeLength(entry.window);
}

export function isOverdueOn(entry: Obligation, today: CivilDate): boolean {
  return !isWithin(today, entry.window.from, entry.window.to);
}

export function sortObligations(entries: readonly Obligation[]): Obligation[] {
  return [...entries].sort((left, right) => {
    const byWindow = compareRanges(left.window, right.window);
    if (byWindow !== 0) return byWindow;
    return left.habitId.localeCompare(right.habitId);
  });
}

export function describeObligation(entry: Obligation): string {
  const times = entry.times === 1 ? 'once' : `${entry.times} times`;
  return `${times} per ${entry.scope} over ${formatDateRange(entry.window)}`;
}

export function obligationsForHabit(
  entries: readonly Obligation[],
  habitId: HabitId,
): Obligation[] {
  return sortObligations(entries.filter((entry) => entry.habitId === habitId));
}

export function totalTimes(entries: readonly Obligation[]): number {
  return entries.reduce((sum, entry) => sum + entry.times, 0);
}
