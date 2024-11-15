import { ConflictError, NotFoundError } from '../errors.js';
import type { CheckInId, HabitId } from '../ids.js';
import type { Instant } from '../time/zone.js';
import { amendReading, compareCheckIns, isVoided, voidCheckIn, type CheckIn } from './checkin.js';

/**
 * The record of what was done. Nothing is ever removed: an entry that turns out
 * to be wrong is voided and stays in place, because a supervisor asking why an
 * adherence figure moved needs to see the entry that moved it.
 */
export interface Ledger {
  readonly entries: readonly CheckIn[];
}

export interface AppendResult {
  readonly ledger: Ledger;
  /** False when an idempotency key had already been used. */
  readonly accepted: boolean;
  readonly entry: CheckIn;
}

export function emptyLedger(): Ledger {
  return { entries: [] };
}

export function ledgerOf(entries: readonly CheckIn[]): Ledger {
  return { entries: [...entries].sort(compareCheckIns) };
}

export function size(ledger: Ledger): number {
  return ledger.entries.length;
}

export function liveEntries(ledger: Ledger): CheckIn[] {
  return ledger.entries.filter((entry) => !isVoided(entry));
}

export function findById(ledger: Ledger, id: CheckInId): CheckIn | undefined {
  return ledger.entries.find((entry) => entry.id === id);
}

export function findByKey(ledger: Ledger, habitId: HabitId, key: string): CheckIn | undefined {
  return ledger.entries.find((entry) => entry.habitId === habitId && entry.idempotencyKey === key);
}

export function append(ledger: Ledger, entry: CheckIn): AppendResult {
  if (findById(ledger, entry.id) !== undefined) {
    throw new ConflictError(`check in ${entry.id} is already in the ledger`);
  }
  if (entry.idempotencyKey !== undefined) {
    const seen = findByKey(ledger, entry.habitId, entry.idempotencyKey);
    if (seen !== undefined) {
      return { ledger, accepted: false, entry: seen };
    }
  }
  const entries = [...ledger.entries, entry].sort(compareCheckIns);
  return { ledger: { entries }, accepted: true, entry };
}

export function appendAll(ledger: Ledger, entries: readonly CheckIn[]): Ledger {
  let current = ledger;
  for (const entry of entries) {
    current = append(current, entry).ledger;
  }
  return current;
}

function replace(ledger: Ledger, id: CheckInId, change: (entry: CheckIn) => CheckIn): Ledger {
  const existing = findById(ledger, id);
  if (existing === undefined) {
    throw new NotFoundError('check in', id);
  }
  return {
    entries: ledger.entries.map((entry) => (entry.id === id ? change(entry) : entry)),
  };
}

export function voidEntry(ledger: Ledger, id: CheckInId, at: Instant): Ledger {
  return replace(ledger, id, (entry) => voidCheckIn(entry, at));
}

export function amendEntry(ledger: Ledger, id: CheckInId, reading: number | undefined): Ledger {
  return replace(ledger, id, (entry) => amendReading(entry, reading));
}

export function entriesForHabit(ledger: Ledger, habitId: HabitId): CheckIn[] {
  return liveEntries(ledger).filter((entry) => entry.habitId === habitId);
}

/** Entries whose event time falls in the half open interval, oldest first. */
export function entriesBetween(ledger: Ledger, from: Instant, to: Instant): CheckIn[] {
  return liveEntries(ledger).filter(
    (entry) => entry.at.epochMs >= from.epochMs && entry.at.epochMs < to.epochMs,
  );
}

export function lastEntry(ledger: Ledger): CheckIn | undefined {
  const live = liveEntries(ledger);
  return live[live.length - 1];
}

export function habitsTouched(ledger: Ledger): HabitId[] {
  return [...new Set(liveEntries(ledger).map((entry) => entry.habitId))].sort();
}
