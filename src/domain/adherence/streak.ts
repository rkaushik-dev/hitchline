import type { CrewMemberId } from '../ids.js';
import { compareCivil, type CivilDate } from '../time/civil.js';
import { isSettled, type ObligationResult } from './match.js';
import { isShielded, type Shield } from './shield.js';

/**
 * A streak counted in obligations rather than in days. A person on a fortnight
 * rotation with a shift day habit owes nothing at home, so their streak has to
 * survive the fortnight without pretending they did anything during it.
 */
export interface StreakSummary {
  readonly current: number;
  readonly longest: number;
  readonly lastMetOn: CivilDate | undefined;
  /** Obligations skipped because the day they fell due on was shielded. */
  readonly shielded: number;
  /** The most recent obligation that broke a run, if any did. */
  readonly brokenOn: CivilDate | undefined;
}

export interface StreakRequest {
  readonly results: readonly ObligationResult[];
  readonly shields: readonly Shield[];
  readonly memberId: CrewMemberId;
}

export const NO_STREAK: StreakSummary = {
  current: 0,
  longest: 0,
  lastMetOn: undefined,
  shielded: 0,
  brokenOn: undefined,
};

function settledInOrder(results: readonly ObligationResult[]): ObligationResult[] {
  return results
    .filter(isSettled)
    .sort((left, right) => compareCivil(left.obligation.dueOn, right.obligation.dueOn));
}

export function computeStreak(request: StreakRequest): StreakSummary {
  let current = 0;
  let longest = 0;
  let shielded = 0;
  let lastMetOn: CivilDate | undefined;
  let brokenOn: CivilDate | undefined;

  for (const result of settledInOrder(request.results)) {
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
    current = 0;
    brokenOn = due;
  }

  return { current, longest, lastMetOn, shielded, brokenOn };
}

/**
 * True when a run is alive and the next thing due has not been done yet. Used
 * to decide whether a nudge is worth sending at all.
 */
export function isAtRisk(summary: StreakSummary, results: readonly ObligationResult[]): boolean {
  if (summary.current === 0) return false;
  return results.some((result) => result.status === 'pending' && result.count === 0);
}

export function streakBroken(summary: StreakSummary): boolean {
  return summary.brokenOn !== undefined && summary.current === 0;
}

/** The best run ever recorded, whether or not it is the one still running. */
export function bestRun(summary: StreakSummary): number {
  return Math.max(summary.current, summary.longest);
}

export function describeStreak(summary: StreakSummary): string {
  if (summary.current === 0) {
    return summary.longest === 0 ? 'no run yet' : `run broken, best was ${summary.longest}`;
  }
  return `${summary.current} in a row, best ${bestRun(summary)}`;
}
