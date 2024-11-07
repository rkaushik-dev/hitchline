import { UnsupportedError } from '../errors.js';
import { addDays, isBefore, type CivilDate } from '../time/civil.js';
import { clampRange, dateRange, daysOf, rangesIntersect, type DateRange } from '../time/range.js';
import { isRunningOn, type Habit } from '../habit/habit.js';
import { hitchNumberOn, hitchRange } from '../rotation/assignment.js';
import { cycleLength, type DayRole } from '../rotation/pattern.js';
import { effectiveBlocks, roleFor, type Roster } from '../rotation/roster.js';
import {
  obligation,
  sortObligations,
  type Obligation,
  type ObligationScope,
} from './obligation.js';

/**
 * Turns a habit into the list of things it actually asks for over a window.
 * The roster is needed as well as the habit, because a cadence tied to shift
 * days asks for nothing at all during a fortnight at home.
 */
export interface Expansion {
  readonly roster: Roster;
  readonly habit: Habit;
  readonly window: DateRange;
}

function withGrace(range: DateRange, graceDays: number): DateRange {
  return dateRange(range.from, addDays(range.to, graceDays));
}

function emit(
  request: Expansion,
  scope: ObligationScope,
  range: DateRange,
  times: number,
): Obligation {
  return obligation({
    habitId: request.habit.id,
    memberId: request.habit.memberId,
    scope,
    window: withGrace(range, request.habit.graceDays),
    times,
  });
}

function expandPerDay(request: Expansion, times: number, roles?: readonly DayRole[]): Obligation[] {
  const entries: Obligation[] = [];
  for (const date of daysOf(request.window)) {
    if (!isRunningOn(request.habit, date)) continue;
    if (roles !== undefined && !roles.includes(roleFor(request.roster, date))) continue;
    entries.push(emit(request, 'day', dateRange(date, date), times));
  }
  return entries;
}

/**
 * One obligation per turn of the rotation. The window is the whole hitch, so
 * somebody who does the thing three times in their first week has finished
 * what the fortnight asked for.
 */
function expandPerHitch(request: Expansion, times: number): Obligation[] {
  const { rotation } = request.roster.member;
  const first = hitchNumberOn(rotation, request.window.from);
  const last = hitchNumberOn(rotation, request.window.to);
  const entries: Obligation[] = [];
  for (let number = first; number <= last; number += 1) {
    const live = habitWindow(request.habit, hitchRange(rotation, number));
    if (live === undefined) continue;
    entries.push(emit(request, 'hitch', live, times));
  }
  return entries;
}

/**
 * One obligation per unbroken run of days in the role. Runs are found over a
 * window widened by a whole cycle at each end, so a block that started before
 * the window still comes back whole.
 */
function expandPerBlock(request: Expansion, times: number, role: DayRole): Obligation[] {
  const cycle = cycleLength(request.roster.member.rotation.pattern);
  const wide = dateRange(addDays(request.window.from, -cycle), addDays(request.window.to, cycle));
  const entries: Obligation[] = [];
  for (const block of effectiveBlocks(request.roster, wide)) {
    if (block.role !== role) continue;
    if (!rangesIntersect(block.range, request.window)) continue;
    const live = habitWindow(request.habit, block.range);
    if (live === undefined) continue;
    entries.push(emit(request, 'block', live, times));
  }
  return entries;
}

export function expandHabit(request: Expansion): Obligation[] {
  const { cadence } = request.habit;
  switch (cadence.kind) {
    case 'per_day':
      return sortObligations(expandPerDay(request, cadence.times));
    case 'per_role_day':
      return sortObligations(expandPerDay(request, cadence.times, cadence.roles));
    case 'per_hitch':
      return sortObligations(expandPerHitch(request, cadence.times));
    case 'per_block':
      return sortObligations(expandPerBlock(request, cadence.times, cadence.role));
    default:
      throw new UnsupportedError(`cadence ${cadence.kind} cannot be expanded yet`);
  }
}

/**
 * Expands several habits over the same window. Every habit has to belong to
 * the person the roster is for, which the caller is expected to have sorted
 * out already.
 */
export function expandAll(
  roster: Roster,
  habits: readonly Habit[],
  window: DateRange,
): Obligation[] {
  const entries: Obligation[] = [];
  for (const entry of habits) {
    entries.push(...expandHabit({ roster, habit: entry, window }));
  }
  return sortObligations(entries);
}

/** Narrows a window to the part of it the habit is alive for. */
export function habitWindow(entry: Habit, window: DateRange): DateRange | undefined {
  const end = entry.endsOn ?? window.to;
  if (isBefore(end, entry.startsOn)) return undefined;
  return clampRange(window, dateRange(entry.startsOn, end));
}

/** The days of a window a habit could be asked about at all. */
export function liveDays(entry: Habit, window: DateRange): CivilDate[] {
  return daysOf(window).filter((date) => isRunningOn(entry, date));
}
