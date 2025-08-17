import { isDomainError } from '../domain/errors.js';
import { habitId as parseHabitId, type CheckInId, type CrewMemberId } from '../domain/ids.js';
import { checkInsService, type RecordCheckInCommand } from './checkins.service.js';
import type { Ports } from './ports.js';

/**
 * A handset that has been out of signal for a fortnight. Everything it queued
 * arrives at once, in whatever order the phone kept it, and it needs a per
 * item answer so it knows which rows to clear and which to keep trying.
 *
 * The whole batch is never rejected. A rig with no signal is the normal case
 * offshore, and an all or nothing sync would mean one bad row costs a
 * fortnight of records.
 */
export type SyncOutcome = 'accepted' | 'already_had_it' | 'refused';

export interface QueuedCheckIn {
  /** The handset's own id for the row, echoed back so it can clear its queue. */
  readonly localId: string;
  readonly habitId: string;
  readonly at: string;
  readonly reading?: number;
  readonly note?: string;
  readonly idempotencyKey?: string;
}

export interface SyncItemResult {
  readonly localId: string;
  readonly outcome: SyncOutcome;
  readonly checkInId: CheckInId | undefined;
  readonly message: string;
}

export interface SyncReport {
  readonly memberId: CrewMemberId;
  readonly results: readonly SyncItemResult[];
  readonly accepted: number;
  readonly duplicates: number;
  readonly refused: number;
}

export interface SyncService {
  push(memberId: CrewMemberId, queued: readonly QueuedCheckIn[]): Promise<SyncReport>;
}

const MAX_BATCH = 500;

function byTime(left: QueuedCheckIn, right: QueuedCheckIn): number {
  return Date.parse(left.at) - Date.parse(right.at);
}

export function syncService(ports: Ports): SyncService {
  const checkIns = checkInsService(ports);

  return {
    async push(memberId: CrewMemberId, queued: readonly QueuedCheckIn[]): Promise<SyncReport> {
      const batch = [...queued].slice(0, MAX_BATCH).sort(byTime);
      const results: SyncItemResult[] = [];
      let accepted = 0;
      let duplicates = 0;
      let refused = 0;

      for (const item of batch) {
        try {
          const command: RecordCheckInCommand = {
            habitId: parseHabitId(item.habitId),
            at: item.at,
            reading: item.reading,
            note: item.note,
            source: 'app',
            // A handset that resends after a dropped connection must not
            // double count, so its own id stands in when it sent no key.
            idempotencyKey: item.idempotencyKey ?? `queued-${item.localId}`,
          };
          const result = await checkIns.record(command);
          if (result.accepted) {
            accepted += 1;
            results.push({
              localId: item.localId,
              outcome: 'accepted',
              checkInId: result.entry.id,
              message: 'stored',
            });
            continue;
          }
          duplicates += 1;
          results.push({
            localId: item.localId,
            outcome: 'already_had_it',
            checkInId: result.entry.id,
            message: 'this one was already here',
          });
        } catch (error) {
          if (!isDomainError(error)) throw error;
          refused += 1;
          results.push({
            localId: item.localId,
            outcome: 'refused',
            checkInId: undefined,
            message: error.message,
          });
        }
      }

      return { memberId, results, accepted, duplicates, refused };
    },
  };
}

/** The local ids a handset may clear: everything that will not change answer. */
export function settledIds(report: SyncReport): string[] {
  return report.results
    .filter((result) => result.outcome !== 'refused')
    .map((result) => result.localId);
}

export function describeSync(report: SyncReport): string {
  return [
    `${String(report.accepted)} stored`,
    `${String(report.duplicates)} already here`,
    `${String(report.refused)} refused`,
  ].join(', ');
}
