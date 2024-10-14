import { ConflictError, ValidationError } from '../errors.js';
import type { CrewMemberId } from '../ids.js';
import { formatCivilDate, type CivilDate } from '../time/civil.js';
import {
  compareRanges,
  formatDateRange,
  rangeContains,
  rangeLength,
  rangesIntersect,
  type DateRange,
} from '../time/range.js';
import type { Instant } from '../time/zone.js';
import type { DayRole } from './pattern.js';

/**
 * Why a run of days does not match the pattern. The reason is kept because a
 * supervisor reading the roster wants to know whether somebody was held over
 * or called out, not merely that the day changed colour.
 */
export type OverrideReason =
  | 'held_over'
  | 'called_out'
  | 'swapped'
  | 'medical'
  | 'training'
  | 'weather';

export interface RosterOverride {
  readonly memberId: CrewMemberId;
  readonly range: DateRange;
  readonly role: DayRole;
  readonly reason: OverrideReason;
  readonly note: string;
  readonly enteredAt: Instant;
}

export interface OverrideInput {
  readonly memberId: CrewMemberId;
  readonly range: DateRange;
  readonly role: DayRole;
  readonly reason: OverrideReason;
  readonly note?: string;
  readonly enteredAt: Instant;
}

const MAX_OVERRIDE_DAYS = 120;
const NOTE_MAX = 240;

export function rosterOverride(input: OverrideInput): RosterOverride {
  const days = rangeLength(input.range);
  if (days > MAX_OVERRIDE_DAYS) {
    throw ValidationError.field(
      'range',
      `covers ${days} days, more than the ${MAX_OVERRIDE_DAYS} allowed in one entry`,
    );
  }
  const note = (input.note ?? '').trim();
  if (note.length > NOTE_MAX) {
    throw ValidationError.field('note', `must be at most ${NOTE_MAX} characters`);
  }
  return {
    memberId: input.memberId,
    range: input.range,
    role: input.role,
    reason: input.reason,
    note,
    enteredAt: input.enteredAt,
  };
}

export function sortOverrides(overrides: readonly RosterOverride[]): RosterOverride[] {
  return [...overrides].sort((left, right) => compareRanges(left.range, right.range));
}

/**
 * Two entries for the same person may not cover the same day. Somebody has to
 * decide which one is right, and it is not this module.
 */
export function checkNoClashes(overrides: readonly RosterOverride[]): void {
  const sorted = sortOverrides(overrides);
  for (let index = 1; index < sorted.length; index += 1) {
    const previous = sorted[index - 1];
    const current = sorted[index];
    if (previous === undefined || current === undefined) continue;
    if (previous.memberId !== current.memberId) continue;
    if (rangesIntersect(previous.range, current.range)) {
      throw new ConflictError(
        `roster entries ${formatDateRange(previous.range)} and ` +
          `${formatDateRange(current.range)} cover the same days`,
      );
    }
  }
}

export function overrideOn(
  overrides: readonly RosterOverride[],
  memberId: CrewMemberId,
  date: CivilDate,
): RosterOverride | undefined {
  return overrides.find((entry) => entry.memberId === memberId && rangeContains(entry.range, date));
}

export function overridesOver(
  overrides: readonly RosterOverride[],
  memberId: CrewMemberId,
  range: DateRange,
): RosterOverride[] {
  return sortOverrides(
    overrides.filter((entry) => entry.memberId === memberId && rangesIntersect(entry.range, range)),
  );
}

export function describeOverride(entry: RosterOverride): string {
  const window = formatDateRange(entry.range);
  const note = entry.note.length > 0 ? ` (${entry.note})` : '';
  return `${window} ${entry.role} because ${entry.reason}${note}`;
}

export function overrideCoversDay(entry: RosterOverride, date: CivilDate): boolean {
  return rangeContains(entry.range, date);
}

export function firstDayOf(entry: RosterOverride): string {
  return formatCivilDate(entry.range.from);
}
