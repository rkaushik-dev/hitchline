import { ValidationError } from '../domain/errors.js';
import type { CheckIn } from '../domain/checkin/checkin.js';
import { addMonths, isBefore, type CivilDate } from '../domain/time/civil.js';
import { civilDateIn, type ZoneId } from '../domain/time/zone.js';

/**
 * How long records are kept. An operator has a retention schedule whether or
 * not a tool has one, and a check in note can hold something a person would
 * rather was not on a disk five years later. Nothing here deletes anything on
 * its own: it says what is old, and a person runs the prune.
 */
export interface RetentionPolicy {
  /** Whole check ins older than this are candidates to go. */
  readonly keepMonths: number;
  /** Notes are cleared earlier than the rows themselves. */
  readonly keepNoteMonths: number;
  /** Never touch anything newer than this, whatever else the policy says. */
  readonly floorMonths: number;
}

export const DEFAULT_RETENTION: RetentionPolicy = {
  keepMonths: 60,
  keepNoteMonths: 24,
  floorMonths: 12,
};

export function retentionPolicy(input: Partial<RetentionPolicy> = {}): RetentionPolicy {
  const policy = { ...DEFAULT_RETENTION, ...input };
  for (const [name, value] of Object.entries(policy)) {
    if (!Number.isInteger(value) || value < 1 || value > 600) {
      throw ValidationError.field(name, `must be a whole number of months from 1 to 600`);
    }
  }
  if (policy.keepNoteMonths > policy.keepMonths) {
    throw ValidationError.field('keepNoteMonths', 'cannot be longer than the row is kept');
  }
  if (policy.floorMonths > policy.keepNoteMonths) {
    throw ValidationError.field('floorMonths', 'cannot be longer than notes are kept');
  }
  return policy;
}

export interface RetentionPlan {
  readonly cutoff: CivilDate;
  readonly noteCutoff: CivilDate;
  readonly expired: readonly CheckIn[];
  readonly toRedact: readonly CheckIn[];
  readonly kept: number;
}

/**
 * Works out what a prune would do without doing it. The floor is applied last
 * so a policy that has been edited into nonsense still cannot reach anything
 * recent.
 */
export function planRetention(
  entries: readonly CheckIn[],
  today: CivilDate,
  zone: ZoneId,
  policy: RetentionPolicy = DEFAULT_RETENTION,
): RetentionPlan {
  const cutoff = addMonths(today, -policy.keepMonths);
  const noteCutoff = addMonths(today, -policy.keepNoteMonths);
  const floor = addMonths(today, -policy.floorMonths);

  const expired: CheckIn[] = [];
  const toRedact: CheckIn[] = [];
  let kept = 0;

  for (const entry of entries) {
    const on = civilDateIn(zone, entry.at);
    if (!isBefore(on, floor)) {
      kept += 1;
      continue;
    }
    if (isBefore(on, cutoff)) {
      expired.push(entry);
      continue;
    }
    if (isBefore(on, noteCutoff) && entry.note.length > 0) {
      toRedact.push(entry);
      continue;
    }
    kept += 1;
  }

  return { cutoff, noteCutoff, expired, toRedact, kept };
}

export function redact(entry: CheckIn): CheckIn {
  return { ...entry, note: '' };
}

export function describePlan(plan: RetentionPlan): string {
  return [
    `${String(plan.expired.length)} to remove`,
    `${String(plan.toRedact.length)} notes to clear`,
    `${String(plan.kept)} kept`,
  ].join(', ');
}
