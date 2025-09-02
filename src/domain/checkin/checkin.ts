import { ValidationError } from '../errors.js';
import type { CheckInId, CrewMemberId, HabitId } from '../ids.js';
import { formatInstant, type Instant } from '../time/zone.js';

/**
 * How a check in reached us. The source matters downstream: a kiosk tap on the
 * platform is trusted for the day it names, while an import can carry any date
 * at all and is held to the backfill rules.
 */
export type CheckInSource = 'app' | 'watch' | 'kiosk' | 'import' | 'supervisor';

const CHECK_IN_SOURCES: readonly CheckInSource[] = [
  'app',
  'watch',
  'kiosk',
  'import',
  'supervisor',
];

export interface CheckIn {
  readonly id: CheckInId;
  readonly habitId: HabitId;
  readonly memberId: CrewMemberId;
  /** When the thing was done. */
  readonly at: Instant;
  /** When we were told about it, never earlier than `at`. */
  readonly recordedAt: Instant;
  readonly reading: number | undefined;
  readonly note: string;
  readonly source: CheckInSource;
  readonly idempotencyKey: string | undefined;
  readonly voidedAt: Instant | undefined;
}

export interface CheckInInput {
  readonly id: CheckInId;
  readonly habitId: HabitId;
  readonly memberId: CrewMemberId;
  readonly at: Instant;
  readonly recordedAt: Instant;
  readonly source: CheckInSource;
  readonly reading?: number;
  readonly note?: string;
  readonly idempotencyKey?: string;
}

const NOTE_MAX = 500;
const KEY_PATTERN = /^[A-Za-z0-9_.:-]{8,64}$/;
const MAX_READING = 100_000;
/** How far ahead of us a handset clock may be before we call it wrong. */
export const CLOCK_SLACK_MS = 5 * 60_000;

export function isCheckInSource(value: string): value is CheckInSource {
  return (CHECK_IN_SOURCES as readonly string[]).includes(value);
}

export function checkIn(input: CheckInInput): CheckIn {
  if (input.recordedAt.epochMs + CLOCK_SLACK_MS < input.at.epochMs) {
    throw ValidationError.field(
      'recordedAt',
      `is before the check in happened, ${formatInstant(input.at)}`,
    );
  }
  const note = (input.note ?? '').trim();
  if (note.length > NOTE_MAX) {
    throw ValidationError.field('note', `must be at most ${NOTE_MAX} characters`);
  }
  if (input.reading !== undefined) {
    if (!Number.isFinite(input.reading) || input.reading < 0) {
      throw ValidationError.field('reading', `must not be negative, got ${input.reading}`);
    }
    if (input.reading > MAX_READING) {
      throw ValidationError.field('reading', `must be at most ${MAX_READING}`);
    }
  }
  if (input.idempotencyKey !== undefined && !KEY_PATTERN.test(input.idempotencyKey)) {
    throw ValidationError.field('idempotencyKey', 'must be 8 to 64 plain characters');
  }
  return {
    id: input.id,
    habitId: input.habitId,
    memberId: input.memberId,
    at: input.at,
    recordedAt: input.recordedAt,
    reading: input.reading,
    note,
    source: input.source,
    idempotencyKey: input.idempotencyKey,
    voidedAt: undefined,
  };
}

export function isVoided(entry: CheckIn): boolean {
  return entry.voidedAt !== undefined;
}

export function voidCheckIn(entry: CheckIn, at: Instant): CheckIn {
  if (isVoided(entry)) return entry;
  if (at.epochMs < entry.recordedAt.epochMs) {
    throw ValidationError.field('at', 'cannot void a check in before it was recorded');
  }
  return { ...entry, voidedAt: at };
}

export function amendReading(entry: CheckIn, reading: number | undefined): CheckIn {
  if (isVoided(entry)) {
    throw ValidationError.field('reading', 'cannot be amended on a voided check in');
  }
  if (reading !== undefined && (!Number.isFinite(reading) || reading < 0)) {
    throw ValidationError.field('reading', `must not be negative, got ${reading}`);
  }
  return { ...entry, reading };
}

/** How long after the fact we were told, in whole minutes. */
export function reportingDelayMinutes(entry: CheckIn): number {
  return Math.max(0, Math.round((entry.recordedAt.epochMs - entry.at.epochMs) / 60_000));
}

export function isBackfilled(entry: CheckIn, thresholdMinutes = 24 * 60): boolean {
  return reportingDelayMinutes(entry) > thresholdMinutes;
}

export function compareCheckIns(left: CheckIn, right: CheckIn): number {
  const byTime = left.at.epochMs - right.at.epochMs;
  if (byTime !== 0) return byTime < 0 ? -1 : 1;
  return left.id.localeCompare(right.id);
}
