import type { CivilDate } from '../time/civil.js';
import { dateRange, daysOf, rangeLength, type DateRange } from '../time/range.js';
import type { CrewMember } from '../crew/member.js';
import { blocksOver, roleOn, type RoleBlock } from './assignment.js';
import type { DayRole } from './pattern.js';
import { overrideOn, overridesOver, type RosterOverride } from './override.js';

/**
 * A member's pattern plus whatever the roster clerk has done to it. Every
 * question about what a day means goes through here rather than through the
 * assignment, because the assignment does not know about held over hitches.
 */
export interface Roster {
  readonly member: CrewMember;
  readonly overrides: readonly RosterOverride[];
}

export type RoleSource = 'pattern' | 'override';

export interface EffectiveRole {
  readonly role: DayRole;
  readonly source: RoleSource;
}

export function roster(member: CrewMember, overrides: readonly RosterOverride[] = []): Roster {
  return { member, overrides };
}

export function effectiveRoleOn(target: Roster, date: CivilDate): EffectiveRole {
  const entry = overrideOn(target.overrides, target.member.id, date);
  if (entry !== undefined) {
    return { role: entry.role, source: 'override' };
  }
  return { role: roleOn(target.member.rotation, date), source: 'pattern' };
}

export function roleFor(target: Roster, date: CivilDate): DayRole {
  return effectiveRoleOn(target, date).role;
}

export function isOnShift(target: Roster, date: CivilDate): boolean {
  return roleFor(target, date) === 'onshift';
}

export function rolesFor(target: Roster, range: DateRange): DayRole[] {
  return daysOf(range).map((date) => roleFor(target, date));
}

/**
 * The runs of same role days over a window, with overrides folded in. Adjacent
 * runs of the same role are joined, so a hitch held over by three days reads as
 * one block of seventeen rather than two blocks that happen to touch.
 */
export function effectiveBlocks(target: Roster, range: DateRange): RoleBlock[] {
  const touched = overridesOver(target.overrides, target.member.id, range);
  if (touched.length === 0) {
    return blocksOver(target.member.rotation, range);
  }
  const blocks: RoleBlock[] = [];
  for (const date of daysOf(range)) {
    const role = roleFor(target, date);
    const last = blocks[blocks.length - 1];
    if (last !== undefined && last.role === role) {
      blocks[blocks.length - 1] = { role, range: dateRange(last.range.from, date) };
      continue;
    }
    blocks.push({ role, range: dateRange(date, date) });
  }
  return blocks;
}

export function countRoleFor(target: Roster, range: DateRange, role: DayRole): number {
  return rolesFor(target, range).filter((entry) => entry === role).length;
}

/** How much of a window the person is expected to be at work for. */
export function dutyDays(target: Roster, range: DateRange): number {
  return countRoleFor(target, range, 'onshift');
}

export function isFullyCovered(target: Roster, range: DateRange): boolean {
  return rolesFor(target, range).length === rangeLength(range);
}
