import { addDays, daysBetween, isBefore } from '../time/civil.js';
import { clampRange, dateRange, daysOf, rangesIntersect, type DateRange } from '../time/range.js';
import { isRunningOn, type Habit } from '../habit/habit.js';
import { asOf, type HabitHistory } from '../habit/revision.js';
import { hitchNumberOn, hitchRange } from '../rotation/assignment.js';
import { cycleLength, type DayRole } from '../rotation/pattern.js';
import { effectiveBlocks, type Roster } from '../rotation/roster.js';
import { buildCalendar, roleThrough } from '../rotation/calendar.js';
import {
  obligation,
  obligationKey,
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
  /** When given, each stretch of the window is expanded against the revision
   * that was in force over it, so a cadence changed in March does not rewrite
   * what January asked for. */
  readonly history?: HabitHistory;
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
  const calendar = roles === undefined ? undefined : buildCalendar(request.roster, request.window);
  for (const date of daysOf(request.window)) {
    if (!isRunningOn(request.habit, date)) continue;
    if (roles !== undefined && calendar !== undefined) {
      if (!roles.includes(roleThrough(calendar, request.roster, date))) continue;
    }
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

/**
 * Tiles the habit's life into windows of a fixed length, starting on the day
 * it began. The tiles do not slide: a five in fourteen days habit is measured
 * over the same fortnights every time, so two people can compare readings.
 */
function expandPerWindow(request: Expansion, times: number, windowDays: number): Obligation[] {
  const anchor = request.habit.startsOn;
  const firstTile = Math.floor(daysBetween(anchor, request.window.from) / windowDays);
  const lastTile = Math.floor(daysBetween(anchor, request.window.to) / windowDays);
  const entries: Obligation[] = [];
  for (let tile = firstTile; tile <= lastTile; tile += 1) {
    const from = addDays(anchor, tile * windowDays);
    const live = habitWindow(request.habit, dateRange(from, addDays(from, windowDays - 1)));
    if (live === undefined) continue;
    entries.push(emit(request, 'window', live, times));
  }
  return entries;
}

function expandOne(request: Expansion): Obligation[] {
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
    case 'per_window':
      return sortObligations(expandPerWindow(request, cadence.times, cadence.windowDays));
  }
}

/**
 * The stretches of a window each revision covers, clipped to the window. A
 * habit with no history behaves as one stretch, which is what every caller
 * that predates revisions gets.
 */
export function revisionSegments(history: HabitHistory, window: DateRange): DateRange[] {
  const segments: DateRange[] = [];
  const revisions = history.revisions;
  for (const [index, revision] of revisions.entries()) {
    const next = revisions[index + 1];
    const from = revision.from;
    const to = next === undefined ? window.to : addDays(next.from, -1);
    if (isBefore(to, from)) continue;
    const clipped = clampRange(dateRange(from, to), window);
    if (clipped !== undefined) segments.push(clipped);
  }
  return segments;
}

export function expandHabit(request: Expansion): Obligation[] {
  const history = request.history;
  if (history === undefined) return expandOne(request);

  const seen = new Map<string, Obligation>();
  for (const segment of revisionSegments(history, request.window)) {
    const habit = asOf(request.habit, history, segment.from);
    for (const entry of expandOne({ ...request, habit, window: segment })) {
      // A hitch that straddles a revision comes back from both stretches. The
      // later revision wins, because that is the one in force when it fell due.
      seen.set(obligationKey(entry), entry);
    }
  }
  return sortObligations([...seen.values()]);
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
