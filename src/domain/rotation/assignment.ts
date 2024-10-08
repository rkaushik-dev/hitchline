import { ValidationError } from '../errors.js';
import {
  addDays,
  daysBetween,
  formatCivilDate,
  isAfter,
  isBefore,
  type CivilDate,
} from '../time/civil.js';
import { dateRange, rangeContains, type DateRange } from '../time/range.js';
import { cycleLength, roleAtIndex, type DayRole, type RotationPattern } from './pattern.js';

/**
 * A pattern pinned to a calendar for one person. Two people on the same rota
 * share a pattern and differ only by `phase`, which is how back to back crews
 * are described: the same 14 and 14, half a cycle apart.
 */
export interface RotationAssignment {
  readonly pattern: RotationPattern;
  /** The date that sits at cycle position `phase`. */
  readonly anchor: CivilDate;
  readonly phase: number;
  readonly effective: DateRange | undefined;
}

export interface RoleBlock {
  readonly role: DayRole;
  readonly range: DateRange;
}

export interface AssignmentInput {
  readonly pattern: RotationPattern;
  readonly anchor: CivilDate;
  readonly phase?: number;
  readonly effective?: DateRange;
}

export function rotationAssignment(input: AssignmentInput): RotationAssignment {
  const phase = input.phase ?? 0;
  if (!Number.isInteger(phase)) {
    throw ValidationError.field('phase', 'must be a whole number of days into the cycle');
  }
  const length = cycleLength(input.pattern);
  if (phase < 0 || phase >= length) {
    throw ValidationError.field('phase', `must be between 0 and ${length - 1}, got ${phase}`);
  }
  if (input.effective !== undefined && isBefore(input.effective.to, input.effective.from)) {
    throw ValidationError.field('effective', 'ends before it starts');
  }
  return {
    pattern: input.pattern,
    anchor: input.anchor,
    phase,
    effective: input.effective,
  };
}

export function isActiveOn(assignment: RotationAssignment, date: CivilDate): boolean {
  if (assignment.effective === undefined) return true;
  return rangeContains(assignment.effective, date);
}

export function cycleIndexOn(assignment: RotationAssignment, date: CivilDate): number {
  const offset = daysBetween(assignment.anchor, date) + assignment.phase;
  const length = cycleLength(assignment.pattern);
  return ((offset % length) + length) % length;
}

export function roleOn(assignment: RotationAssignment, date: CivilDate): DayRole {
  return roleAtIndex(assignment.pattern, cycleIndexOn(assignment, date));
}

/**
 * Which repetition of the cycle the date falls in, counting the one containing
 * the anchor as zero. Negative before the anchor.
 */
export function hitchNumberOn(assignment: RotationAssignment, date: CivilDate): number {
  const offset = daysBetween(assignment.anchor, date) + assignment.phase;
  return Math.floor(offset / cycleLength(assignment.pattern));
}

export function hitchRange(assignment: RotationAssignment, hitchNumber: number): DateRange {
  const length = cycleLength(assignment.pattern);
  const startOffset = hitchNumber * length - assignment.phase;
  const from = addDays(assignment.anchor, startOffset);
  return dateRange(from, addDays(from, length - 1));
}

export function hitchRangeOn(assignment: RotationAssignment, date: CivilDate): DateRange {
  return hitchRange(assignment, hitchNumberOn(assignment, date));
}

/** The unbroken run of days sharing the role of `date`. */
export function blockContaining(assignment: RotationAssignment, date: CivilDate): RoleBlock {
  const role = roleOn(assignment, date);
  const length = cycleLength(assignment.pattern);
  let from = date;
  for (let step = 1; step < length; step += 1) {
    const candidate = addDays(date, -step);
    if (roleOn(assignment, candidate) !== role) break;
    from = candidate;
  }
  let to = date;
  for (let step = 1; step < length; step += 1) {
    const candidate = addDays(date, step);
    if (roleOn(assignment, candidate) !== role) break;
    to = candidate;
  }
  return { role, range: dateRange(from, to) };
}

/** The first date after `date` whose role differs from the role on `date`. */
export function nextRoleChange(assignment: RotationAssignment, date: CivilDate): CivilDate {
  return addDays(blockContaining(assignment, date).range.to, 1);
}

export function rolesOver(assignment: RotationAssignment, range: DateRange): DayRole[] {
  const roles: DayRole[] = [];
  const span = daysBetween(range.from, range.to);
  for (let step = 0; step <= span; step += 1) {
    roles.push(roleOn(assignment, addDays(range.from, step)));
  }
  return roles;
}

export function countRoleOver(
  assignment: RotationAssignment,
  range: DateRange,
  role: DayRole,
): number {
  return rolesOver(assignment, range).filter((entry) => entry === role).length;
}

/** Splits a range into the runs of same role days it is made of. */
export function blocksOver(assignment: RotationAssignment, range: DateRange): RoleBlock[] {
  const blocks: RoleBlock[] = [];
  let cursor = range.from;
  while (!isAfter(cursor, range.to)) {
    const block = blockContaining(assignment, cursor);
    const from = isBefore(block.range.from, range.from) ? range.from : block.range.from;
    const to = isAfter(block.range.to, range.to) ? range.to : block.range.to;
    blocks.push({ role: block.role, range: dateRange(from, to) });
    cursor = addDays(block.range.to, 1);
  }
  return blocks;
}

export function describeAssignment(assignment: RotationAssignment): string {
  const anchor = formatCivilDate(assignment.anchor);
  return `${assignment.pattern.segments.length} segment cycle anchored ${anchor} phase ${assignment.phase}`;
}
