import { checkIn, type CheckIn } from '../../src/domain/checkin/checkin.js';
import { obligation, type Obligation } from '../../src/domain/schedule/obligation.js';
import type { AdherenceStatus, ObligationResult } from '../../src/domain/adherence/match.js';
import { checkInId, crewMemberId, habitId, type HabitId } from '../../src/domain/ids.js';
import { parseDateRange } from '../../src/domain/time/range.js';
import { parseInstant } from '../../src/domain/time/zone.js';

/**
 * Builders the tests share. Six files were each keeping their own `at` and
 * `result` helper and they had drifted: two of them recorded a check in with
 * `recordedAt` before `at`, which the domain now refuses, so the copies had
 * quietly stopped agreeing about what a valid row looks like.
 */
export const ADA = crewMemberId('mem_0123456789ABCDEF');
export const BO = crewMemberId('mem_FEDCBA9876543210');
export const TREADMILL = habitId('hab_0123456789ABCDEF');
export const STRETCHES = habitId('hab_FEDCBA9876543210');

export interface CheckInOptions {
  readonly id?: string;
  readonly habit?: HabitId;
  readonly reading?: number;
  readonly note?: string;
  readonly key?: string;
  readonly recordedAt?: string;
}

let minted = 0;

/** A check in that happened, and was reported, at the same moment. */
export function aCheckIn(iso: string, options: CheckInOptions = {}): CheckIn {
  minted += 1;
  const id = options.id ?? `chk_${minted.toString().padStart(16, '0')}`;
  return checkIn({
    id: checkInId(id),
    habitId: options.habit ?? TREADMILL,
    memberId: ADA,
    at: parseInstant(iso),
    recordedAt: parseInstant(options.recordedAt ?? iso),
    source: 'app',
    reading: options.reading,
    note: options.note,
    idempotencyKey: options.key,
  });
}

export function resetCheckInIds(): void {
  minted = 0;
}

/** A day scoped obligation on one date, which is what most scoring tests want. */
export function anObligation(day: string, times = 1, habit: HabitId = TREADMILL): Obligation {
  return obligation({
    habitId: habit,
    memberId: ADA,
    scope: 'day',
    window: parseDateRange(`${day}..${day}`),
    times,
  });
}

export function aResult(
  day: string,
  status: AdherenceStatus,
  habit: HabitId = TREADMILL,
): ObligationResult {
  return {
    obligation: anObligation(day, 1, habit),
    count: status === 'met' ? 1 : 0,
    total: undefined,
    status,
    progress: status === 'met' ? 1 : 0,
  };
}

/** A run of results, one a day from the date given. */
export function aRun(from: string, statuses: readonly AdherenceStatus[]): ObligationResult[] {
  const start = Date.parse(`${from}T00:00:00Z`);
  return statuses.map((status, index) => {
    const day = new Date(start + index * 86_400_000).toISOString().slice(0, 10);
    return aResult(day, status);
  });
}
