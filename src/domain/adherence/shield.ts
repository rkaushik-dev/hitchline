import { ValidationError } from '../errors.js';
import type { CrewMemberId } from '../ids.js';
import type { CivilDate } from '../time/civil.js';
import {
  daysOf,
  mergeRanges,
  rangeContains,
  rangeLength,
  rangesIntersect,
  type DateRange,
} from '../time/range.js';
import type { Instant } from '../time/zone.js';

/**
 * A run of days a habit is not held against somebody. A shielded day neither
 * extends a streak nor breaks it, which is the difference between a tool a
 * crew keeps using after a bereavement and one they delete.
 */
export type ShieldReason =
  | 'annual_leave'
  | 'sick'
  | 'compassionate'
  | 'training'
  | 'medical_advice'
  | 'operational';

export interface Shield {
  readonly memberId: CrewMemberId;
  readonly range: DateRange;
  readonly reason: ShieldReason;
  readonly note: string;
  /** Only a supervisor may shield somebody else, and we keep who did it. */
  readonly grantedBy: CrewMemberId | undefined;
  readonly grantedAt: Instant;
}

export interface ShieldInput {
  readonly memberId: CrewMemberId;
  readonly range: DateRange;
  readonly reason: ShieldReason;
  readonly grantedAt: Instant;
  readonly note?: string;
  readonly grantedBy?: CrewMemberId;
}

const MAX_SHIELD_DAYS = 180;
const NOTE_MAX = 240;

/** Reasons a member may claim without a supervisor signing it off. */
const SELF_SERVE: readonly ShieldReason[] = ['sick', 'annual_leave'];

export function shield(input: ShieldInput): Shield {
  const days = rangeLength(input.range);
  if (days > MAX_SHIELD_DAYS) {
    throw ValidationError.field(
      'range',
      `covers ${days} days, more than the ${MAX_SHIELD_DAYS} allowed`,
    );
  }
  const note = (input.note ?? '').trim();
  if (note.length > NOTE_MAX) {
    throw ValidationError.field('note', `must be at most ${NOTE_MAX} characters`);
  }
  if (!SELF_SERVE.includes(input.reason) && input.grantedBy === undefined) {
    throw ValidationError.field('grantedBy', `is needed to shield days for ${input.reason}`);
  }
  return {
    memberId: input.memberId,
    range: input.range,
    reason: input.reason,
    note,
    grantedBy: input.grantedBy,
    grantedAt: input.grantedAt,
  };
}

export function shieldsFor(shields: readonly Shield[], memberId: CrewMemberId): Shield[] {
  return shields.filter((entry) => entry.memberId === memberId);
}

export function shieldOn(
  shields: readonly Shield[],
  memberId: CrewMemberId,
  date: CivilDate,
): Shield | undefined {
  return shields.find((entry) => entry.memberId === memberId && rangeContains(entry.range, date));
}

export function isShielded(
  shields: readonly Shield[],
  memberId: CrewMemberId,
  date: CivilDate,
): boolean {
  return shieldOn(shields, memberId, date) !== undefined;
}

export function shieldsOver(
  shields: readonly Shield[],
  memberId: CrewMemberId,
  range: DateRange,
): Shield[] {
  return shieldsFor(shields, memberId).filter((entry) => rangesIntersect(entry.range, range));
}

/** The distinct days covered, so two overlapping entries are counted once. */
export function shieldedDayCount(
  shields: readonly Shield[],
  memberId: CrewMemberId,
  range: DateRange,
): number {
  const covering = shieldsOver(shields, memberId, range).map((entry) => entry.range);
  let count = 0;
  for (const date of daysOf(range)) {
    if (covering.some((window) => rangeContains(window, date))) count += 1;
  }
  return count;
}

export function coveredRanges(shields: readonly Shield[], memberId: CrewMemberId): DateRange[] {
  return mergeRanges(shieldsFor(shields, memberId).map((entry) => entry.range));
}

export function describeShield(entry: Shield): string {
  const who = entry.grantedBy === undefined ? 'self declared' : `granted by ${entry.grantedBy}`;
  return `${entry.reason} ${who}`;
}
