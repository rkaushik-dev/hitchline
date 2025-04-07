import { ConflictError, NotFoundError } from '../errors.js';
import type { CheckInId, HabitId } from '../ids.js';
import type { Instant } from '../time/zone.js';
import { amendReading, compareCheckIns, isVoided, voidCheckIn, type CheckIn } from './checkin.js';

/**
 * The record of what was done. Nothing is ever removed: an entry that turns out
 * to be wrong is voided and stays in place, because a supervisor asking why an
 * adherence figure moved needs to see the entry that moved it.
 *
 * Lookups go through an index built when the ledger is made. Building it costs
 * a pass over the entries, which the sort already costs, and it takes the id
 * and idempotency lookups off a linear scan. A hitch of check ins for a crew
 * of forty is a few thousand rows, and the import path asks for a key on every
 * one of them.
 */
export interface Ledger {
  readonly entries: readonly CheckIn[];
  readonly byId: ReadonlyMap<string, CheckIn>;
  readonly byKey: ReadonlyMap<string, CheckIn>;
  readonly liveByHabit: ReadonlyMap<string, readonly CheckIn[]>;
}

export interface AppendResult {
  readonly ledger: Ledger;
  /** False when an idempotency key had already been used. */
  readonly accepted: boolean;
  readonly entry: CheckIn;
}

function keyOf(entry: CheckIn): string | undefined {
  return entry.idempotencyKey === undefined
    ? undefined
    : `${entry.habitId}:${entry.idempotencyKey}`;
}

function index(entries: readonly CheckIn[]): Ledger {
  const byId = new Map<string, CheckIn>();
  const byKey = new Map<string, CheckIn>();
  const liveByHabit = new Map<string, CheckIn[]>();

  for (const entry of entries) {
    byId.set(entry.id, entry);
    const key = keyOf(entry);
    if (key !== undefined && !byKey.has(key)) byKey.set(key, entry);
    if (isVoided(entry)) continue;
    const held = liveByHabit.get(entry.habitId);
    if (held === undefined) liveByHabit.set(entry.habitId, [entry]);
    else held.push(entry);
  }

  return { entries, byId, byKey, liveByHabit };
}

export function emptyLedger(): Ledger {
  return index([]);
}

export function ledgerOf(entries: readonly CheckIn[]): Ledger {
  return index([...entries].sort(compareCheckIns));
}

export function size(ledger: Ledger): number {
  return ledger.entries.length;
}

export function liveEntries(ledger: Ledger): CheckIn[] {
  return ledger.entries.filter((entry) => !isVoided(entry));
}

export function findById(ledger: Ledger, id: CheckInId): CheckIn | undefined {
  return ledger.byId.get(id);
}

export function findByKey(ledger: Ledger, habitId: HabitId, key: string): CheckIn | undefined {
  return ledger.byKey.get(`${habitId}:${key}`);
}

export function append(ledger: Ledger, entry: CheckIn): AppendResult {
  if (ledger.byId.has(entry.id)) {
    throw new ConflictError(`check in ${entry.id} is already in the ledger`);
  }
  const key = keyOf(entry);
  if (key !== undefined) {
    const seen = ledger.byKey.get(key);
    if (seen !== undefined) {
      return { ledger, accepted: false, entry: seen };
    }
  }
  return { ledger: ledgerOf([...ledger.entries, entry]), accepted: true, entry };
}

export function appendAll(ledger: Ledger, entries: readonly CheckIn[]): Ledger {
  let current = ledger;
  for (const entry of entries) {
    current = append(current, entry).ledger;
  }
  return current;
}

function replace(ledger: Ledger, id: CheckInId, change: (entry: CheckIn) => CheckIn): Ledger {
  if (!ledger.byId.has(id)) {
    throw new NotFoundError('check in', id);
  }
  return index(ledger.entries.map((entry) => (entry.id === id ? change(entry) : entry)));
}

export function voidEntry(ledger: Ledger, id: CheckInId, at: Instant): Ledger {
  return replace(ledger, id, (entry) => voidCheckIn(entry, at));
}

export function amendEntry(ledger: Ledger, id: CheckInId, reading: number | undefined): Ledger {
  return replace(ledger, id, (entry) => amendReading(entry, reading));
}

export function entriesForHabit(ledger: Ledger, habitId: HabitId): CheckIn[] {
  return [...(ledger.liveByHabit.get(habitId) ?? [])];
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
  return [...ledger.liveByHabit.keys()].sort() as HabitId[];
}
