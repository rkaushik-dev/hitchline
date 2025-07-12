import { addDays, daysBetween, formatCivilDate, type CivilDate } from '../time/civil.js';
import { dateRange, rangeContains, type DateRange } from '../time/range.js';
import type { DayRole } from './pattern.js';
import { roleFor, type Roster } from './roster.js';

/**
 * A window of days with the role of each one worked out once. Expanding a
 * dozen habits over a year asked the roster the same question a few thousand
 * times, and every one of those walked the override list. The calendar is
 * built once per report and answers from an array.
 */
export interface RoleCalendar {
  readonly window: DateRange;
  readonly roles: readonly DayRole[];
}

export function buildCalendar(roster: Roster, window: DateRange): RoleCalendar {
  const span = daysBetween(window.from, window.to);
  const roles: DayRole[] = [];
  for (let offset = 0; offset <= span; offset += 1) {
    roles.push(roleFor(roster, addDays(window.from, offset)));
  }
  return { window, roles };
}

export function roleOnDay(calendar: RoleCalendar, date: CivilDate): DayRole | undefined {
  if (!rangeContains(calendar.window, date)) return undefined;
  return calendar.roles[daysBetween(calendar.window.from, date)];
}

/** Reads from the calendar when the day is inside it, and asks the roster if not. */
export function roleThrough(calendar: RoleCalendar, roster: Roster, date: CivilDate): DayRole {
  return roleOnDay(calendar, date) ?? roleFor(roster, date);
}

export interface RoleBlockRange {
  readonly role: DayRole;
  readonly range: DateRange;
}

/** The runs of same role days inside the calendar, in order. */
export function blocksIn(calendar: RoleCalendar): RoleBlockRange[] {
  const blocks: RoleBlockRange[] = [];
  for (const [offset, role] of calendar.roles.entries()) {
    const date = addDays(calendar.window.from, offset);
    const last = blocks[blocks.length - 1];
    if (last !== undefined && last.role === role) {
      blocks[blocks.length - 1] = { role, range: dateRange(last.range.from, date) };
      continue;
    }
    blocks.push({ role, range: dateRange(date, date) });
  }
  return blocks;
}

export function countIn(calendar: RoleCalendar, role: DayRole): number {
  return calendar.roles.filter((entry) => entry === role).length;
}

export function describeCalendar(calendar: RoleCalendar): string {
  const from = formatCivilDate(calendar.window.from);
  const to = formatCivilDate(calendar.window.to);
  return `${from} to ${to}, ${String(calendar.roles.length)} days`;
}
