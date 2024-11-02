import { UnsupportedError } from '../errors.js';
import { addDays, isBefore, type CivilDate } from '../time/civil.js';
import { clampRange, dateRange, daysOf, type DateRange } from '../time/range.js';
import { isRunningOn, type Habit } from '../habit/habit.js';
import { roleFor, type Roster } from '../rotation/roster.js';
import { obligation, sortObligations, type Obligation } from './obligation.js';

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

function graceWindow(day: CivilDate, graceDays: number): DateRange {
  return dateRange(day, addDays(day, graceDays));
}

function expandPerDay(request: Expansion, times: number): Obligation[] {
  const entries: Obligation[] = [];
  for (const date of daysOf(request.window)) {
    if (!isRunningOn(request.habit, date)) continue;
    entries.push(
      obligation({
        habitId: request.habit.id,
        memberId: request.habit.memberId,
        scope: 'day',
        window: graceWindow(date, request.habit.graceDays),
        times,
      }),
    );
  }
  return entries;
}

function expandPerRoleDay(
  request: Expansion,
  times: number,
  roles: readonly string[],
): Obligation[] {
  const entries: Obligation[] = [];
  for (const date of daysOf(request.window)) {
    if (!isRunningOn(request.habit, date)) continue;
    if (!roles.includes(roleFor(request.roster, date))) continue;
    entries.push(
      obligation({
        habitId: request.habit.id,
        memberId: request.habit.memberId,
        scope: 'day',
        window: graceWindow(date, request.habit.graceDays),
        times,
      }),
    );
  }
  return entries;
}

export function expandHabit(request: Expansion): Obligation[] {
  const { cadence } = request.habit;
  switch (cadence.kind) {
    case 'per_day':
      return sortObligations(expandPerDay(request, cadence.times));
    case 'per_role_day':
      return sortObligations(expandPerRoleDay(request, cadence.times, cadence.roles));
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
