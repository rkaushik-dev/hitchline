import { ValidationError } from '../errors.js';
import { isDayRole, type DayRole } from '../rotation/pattern.js';

/**
 * How often a habit is owed. Four of the five shapes here are anchored to the
 * rotation rather than to the calendar, which is the whole point of the thing:
 * "twice on every shift day" and "three times a hitch" are different promises
 * and neither one is a weekly count.
 */
export type CadenceKind = 'per_day' | 'per_role_day' | 'per_hitch' | 'per_block' | 'per_window';

export interface PerDayCadence {
  readonly kind: 'per_day';
  readonly times: number;
}

export interface PerRoleDayCadence {
  readonly kind: 'per_role_day';
  readonly roles: readonly DayRole[];
  readonly times: number;
}

export interface PerHitchCadence {
  readonly kind: 'per_hitch';
  readonly times: number;
}

/** Once per unbroken run of days in the named role, however long the run is. */
export interface PerBlockCadence {
  readonly kind: 'per_block';
  readonly role: DayRole;
  readonly times: number;
}

export interface PerWindowCadence {
  readonly kind: 'per_window';
  readonly times: number;
  readonly windowDays: number;
}

export type Cadence =
  | PerDayCadence
  | PerRoleDayCadence
  | PerHitchCadence
  | PerBlockCadence
  | PerWindowCadence;

const MAX_TIMES = 48;
const MIN_WINDOW = 2;
const MAX_WINDOW = 120;

function checkTimes(times: number): number {
  if (!Number.isInteger(times) || times < 1) {
    throw ValidationError.field('times', `must be a whole number above zero, got ${times}`);
  }
  if (times > MAX_TIMES) {
    throw ValidationError.field('times', `must be at most ${MAX_TIMES}, got ${times}`);
  }
  return times;
}

export function perDay(times: number): PerDayCadence {
  return { kind: 'per_day', times: checkTimes(times) };
}

export function perRoleDay(roles: readonly DayRole[], times: number): PerRoleDayCadence {
  if (roles.length === 0) {
    throw ValidationError.field('roles', 'must name at least one day role');
  }
  const unique = [...new Set(roles)];
  return { kind: 'per_role_day', roles: unique, times: checkTimes(times) };
}

export function perHitch(times: number): PerHitchCadence {
  return { kind: 'per_hitch', times: checkTimes(times) };
}

export function perBlock(role: DayRole, times: number): PerBlockCadence {
  return { kind: 'per_block', role, times: checkTimes(times) };
}

export function perWindow(times: number, windowDays: number): PerWindowCadence {
  if (!Number.isInteger(windowDays) || windowDays < MIN_WINDOW || windowDays > MAX_WINDOW) {
    throw ValidationError.field(
      'windowDays',
      `must be between ${MIN_WINDOW} and ${MAX_WINDOW} days, got ${windowDays}`,
    );
  }
  return { kind: 'per_window', times: checkTimes(times), windowDays };
}

const UNIT_PATTERN = /^(\d+)\/(.+)$/;
const WINDOW_UNIT = /^(\d+)d$/;
const ROLE_DAY_UNIT = /^([a-z+]+)-day$/;
const BLOCK_UNIT = /^([a-z]+)-block$/;

function readRole(word: string, field: string): DayRole {
  const full = word === 'on' ? 'onshift' : word === 'off' ? 'offshift' : word;
  if (!isDayRole(full)) {
    throw ValidationError.field(field, `does not name a day role: ${word}`);
  }
  return full;
}

/**
 * Reads the short forms people type: "2/day", "1/onshift-day", "3/hitch",
 * "1/onshift-block", "5/14d".
 */
export function parseCadence(text: string): Cadence {
  const trimmed = text.trim().toLowerCase();
  const match = UNIT_PATTERN.exec(trimmed);
  if (match === null) {
    throw ValidationError.field('cadence', `must look like 3/hitch, got ${text}`);
  }
  const times = Number(match[1]);
  const unit = match[2] ?? '';

  if (unit === 'day') return perDay(times);
  if (unit === 'hitch') return perHitch(times);

  const window = WINDOW_UNIT.exec(unit);
  if (window !== null) return perWindow(times, Number(window[1]));

  const roleDay = ROLE_DAY_UNIT.exec(unit);
  if (roleDay !== null) {
    const words = (roleDay[1] ?? '').split('+').filter((word) => word.length > 0);
    return perRoleDay(
      words.map((word) => readRole(word, 'cadence')),
      times,
    );
  }

  const block = BLOCK_UNIT.exec(unit);
  if (block !== null) return perBlock(readRole(block[1] ?? '', 'cadence'), times);

  throw ValidationError.field('cadence', `does not know the unit ${unit}`);
}

export function formatCadence(cadence: Cadence): string {
  switch (cadence.kind) {
    case 'per_day':
      return `${cadence.times}/day`;
    case 'per_role_day':
      return `${cadence.times}/${cadence.roles.join('+')}-day`;
    case 'per_hitch':
      return `${cadence.times}/hitch`;
    case 'per_block':
      return `${cadence.times}/${cadence.role}-block`;
    case 'per_window':
      return `${cadence.times}/${cadence.windowDays}d`;
  }
}

/** True when the cadence only ever asks for something on certain kinds of day. */
export function isRoleBound(cadence: Cadence): boolean {
  return cadence.kind === 'per_role_day' || cadence.kind === 'per_block';
}

export function rolesTouched(cadence: Cadence): readonly DayRole[] {
  if (cadence.kind === 'per_role_day') return cadence.roles;
  if (cadence.kind === 'per_block') return [cadence.role];
  return [];
}
