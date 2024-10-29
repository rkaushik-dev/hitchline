import { ValidationError } from '../errors.js';
import type { CrewMemberId, HabitId } from '../ids.js';
import { isBefore, isWithin, type CivilDate } from '../time/civil.js';
import type { Instant } from '../time/zone.js';
import { formatCadence, type Cadence } from './cadence.js';
import { formatTarget, type HabitTarget } from './target.js';

export type HabitStatus = 'active' | 'paused' | 'archived';

export interface Habit {
  readonly id: HabitId;
  readonly memberId: CrewMemberId;
  readonly name: string;
  readonly cadence: Cadence;
  readonly target: HabitTarget | undefined;
  readonly status: HabitStatus;
  readonly startsOn: CivilDate;
  readonly endsOn: CivilDate | undefined;
  readonly tags: readonly string[];
  readonly createdAt: Instant;
  /** Days after the day it was owed on that a check in still counts. */
  readonly graceDays: number;
}

export interface HabitInput {
  readonly id: HabitId;
  readonly memberId: CrewMemberId;
  readonly name: string;
  readonly cadence: Cadence;
  readonly startsOn: CivilDate;
  readonly createdAt: Instant;
  readonly target?: HabitTarget;
  readonly endsOn?: CivilDate;
  readonly tags?: readonly string[];
  readonly status?: HabitStatus;
  readonly graceDays?: number;
}

const NAME_MIN = 3;
const NAME_MAX = 100;
const MAX_TAGS = 8;
const TAG_PATTERN = /^[a-z0-9][a-z0-9-]{0,23}$/;
const MAX_GRACE_DAYS = 7;

const ALLOWED_MOVES: Record<HabitStatus, readonly HabitStatus[]> = {
  active: ['paused', 'archived'],
  paused: ['active', 'archived'],
  archived: [],
};

export function checkHabitName(raw: string): string {
  const name = raw.replace(/\s+/g, ' ').trim();
  if (name.length < NAME_MIN) {
    throw ValidationError.field('name', `must be at least ${NAME_MIN} characters`);
  }
  if (name.length > NAME_MAX) {
    throw ValidationError.field('name', `must be at most ${NAME_MAX} characters`);
  }
  return name;
}

export function checkTags(raw: readonly string[]): string[] {
  const tags = [...new Set(raw.map((tag) => tag.trim().toLowerCase()))];
  if (tags.length > MAX_TAGS) {
    throw ValidationError.field('tags', `must be at most ${MAX_TAGS} of them`);
  }
  for (const tag of tags) {
    if (!TAG_PATTERN.test(tag)) {
      throw ValidationError.field('tags', `has an unusable tag: ${tag}`);
    }
  }
  return tags.sort();
}

export function habit(input: HabitInput): Habit {
  if (input.endsOn !== undefined && isBefore(input.endsOn, input.startsOn)) {
    throw ValidationError.field('endsOn', 'is before the habit starts');
  }
  const graceDays = input.graceDays ?? 0;
  if (!Number.isInteger(graceDays) || graceDays < 0 || graceDays > MAX_GRACE_DAYS) {
    throw ValidationError.field('graceDays', `must be between 0 and ${MAX_GRACE_DAYS}`);
  }
  return {
    id: input.id,
    memberId: input.memberId,
    name: checkHabitName(input.name),
    cadence: input.cadence,
    target: input.target,
    status: input.status ?? 'active',
    startsOn: input.startsOn,
    endsOn: input.endsOn,
    tags: checkTags(input.tags ?? []),
    createdAt: input.createdAt,
    graceDays,
  };
}

/** True when the habit is meant to be asking for something on that date. */
export function isRunningOn(entry: Habit, date: CivilDate): boolean {
  if (entry.status !== 'active') return false;
  if (isBefore(date, entry.startsOn)) return false;
  if (entry.endsOn === undefined) return true;
  return isWithin(date, entry.startsOn, entry.endsOn);
}

export function moveHabitTo(entry: Habit, status: HabitStatus): Habit {
  if (entry.status === status) return entry;
  if (!ALLOWED_MOVES[entry.status].includes(status)) {
    throw ValidationError.field('status', `cannot go from ${entry.status} to ${status}`);
  }
  return { ...entry, status };
}

export function renameHabit(entry: Habit, name: string): Habit {
  return { ...entry, name: checkHabitName(name) };
}

export function retarget(entry: Habit, target: HabitTarget | undefined): Habit {
  if (entry.status === 'archived') {
    throw ValidationError.field('target', 'cannot be changed on an archived habit');
  }
  return { ...entry, target };
}

export function recadence(entry: Habit, cadence: Cadence): Habit {
  if (entry.status === 'archived') {
    throw ValidationError.field('cadence', 'cannot be changed on an archived habit');
  }
  return { ...entry, cadence };
}

export function retag(entry: Habit, tags: readonly string[]): Habit {
  return { ...entry, tags: checkTags(tags) };
}

export function hasTag(entry: Habit, tag: string): boolean {
  return entry.tags.includes(tag.trim().toLowerCase());
}

export function describeHabit(entry: Habit): string {
  const target = entry.target === undefined ? '' : `, ${formatTarget(entry.target)}`;
  return `${entry.name} ${formatCadence(entry.cadence)}${target}`;
}
