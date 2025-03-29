import { ConflictError, ValidationError } from '../errors.js';
import type { HabitId } from '../ids.js';
import { compareCivil, formatCivilDate, isBefore, type CivilDate } from '../time/civil.js';
import { formatCadence, type Cadence } from './cadence.js';
import { formatTarget, type HabitTarget } from './target.js';
import type { Habit } from './habit.js';

/**
 * What a habit asked for, and from when. Changing a habit used to rewrite it,
 * which quietly rewrote history too: a report for January would be scored
 * against a cadence that only started in March. A revision is dated, and a
 * report reads the revision that was in force on the day.
 */
export interface HabitRevision {
  readonly habitId: HabitId;
  readonly version: number;
  readonly from: CivilDate;
  readonly cadence: Cadence;
  readonly target: HabitTarget | undefined;
  readonly graceDays: number;
  readonly note: string;
}

export interface RevisionInput {
  readonly from: CivilDate;
  readonly cadence: Cadence;
  readonly target?: HabitTarget;
  readonly graceDays?: number;
  readonly note?: string;
}

export interface HabitHistory {
  readonly habitId: HabitId;
  readonly revisions: readonly HabitRevision[];
}

const NOTE_MAX = 240;

/** The history a habit starts with: one revision from the day it began. */
export function historyOf(habit: Habit): HabitHistory {
  return {
    habitId: habit.id,
    revisions: [
      {
        habitId: habit.id,
        version: 1,
        from: habit.startsOn,
        cadence: habit.cadence,
        target: habit.target,
        graceDays: habit.graceDays,
        note: 'as created',
      },
    ],
  };
}

export function revise(history: HabitHistory, input: RevisionInput): HabitHistory {
  const last = history.revisions[history.revisions.length - 1];
  if (last === undefined) {
    throw new ConflictError(`habit ${history.habitId} has no revisions to follow`);
  }
  if (isBefore(input.from, last.from)) {
    throw ValidationError.field(
      'from',
      `is before the revision in force since ${formatCivilDate(last.from)}`,
    );
  }
  if (compareCivil(input.from, last.from) === 0) {
    throw new ConflictError(`a revision already starts on ${formatCivilDate(input.from)}`);
  }
  const note = (input.note ?? '').trim();
  if (note.length > NOTE_MAX) {
    throw ValidationError.field('note', `must be at most ${NOTE_MAX} characters`);
  }
  const graceDays = input.graceDays ?? last.graceDays;
  if (!Number.isInteger(graceDays) || graceDays < 0 || graceDays > 7) {
    throw ValidationError.field('graceDays', 'must be between 0 and 7');
  }
  return {
    habitId: history.habitId,
    revisions: [
      ...history.revisions,
      {
        habitId: history.habitId,
        version: last.version + 1,
        from: input.from,
        cadence: input.cadence,
        target: input.target,
        graceDays,
        note,
      },
    ],
  };
}

/** The revision in force on a date, or nothing when the habit had not started. */
export function effectiveOn(history: HabitHistory, date: CivilDate): HabitRevision | undefined {
  let found: HabitRevision | undefined;
  for (const revision of history.revisions) {
    if (isBefore(date, revision.from)) break;
    found = revision;
  }
  return found;
}

export function latest(history: HabitHistory): HabitRevision | undefined {
  return history.revisions[history.revisions.length - 1];
}

export function versionCount(history: HabitHistory): number {
  return history.revisions.length;
}

/** Applies the revision in force on a date to a habit, for scoring that day. */
export function asOf(habit: Habit, history: HabitHistory, date: CivilDate): Habit {
  const revision = effectiveOn(history, date);
  if (revision === undefined) return habit;
  return {
    ...habit,
    cadence: revision.cadence,
    target: revision.target,
    graceDays: revision.graceDays,
  };
}

export function describeRevision(revision: HabitRevision): string {
  const target = revision.target === undefined ? '' : `, ${formatTarget(revision.target)}`;
  const note = revision.note.length === 0 ? '' : ` (${revision.note})`;
  const from = formatCivilDate(revision.from);
  return `v${String(revision.version)} from ${from}: ${formatCadence(revision.cadence)}${target}${note}`;
}
