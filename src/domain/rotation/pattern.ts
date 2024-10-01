import { ValidationError } from '../errors.js';

/**
 * What a given day means for the person working it. Everything downstream keys
 * off these four, because "did they do it today" is a different question on a
 * travel day than on a shift day.
 */
export type DayRole = 'onshift' | 'offshift' | 'travel' | 'standby';

export const DAY_ROLES: readonly DayRole[] = ['onshift', 'offshift', 'travel', 'standby'];

export interface RotationSegment {
  readonly role: DayRole;
  readonly days: number;
}

/**
 * A repeating cycle of day roles. The pattern says nothing about when it
 * starts, which is what makes it shareable between everybody on the same rota.
 */
export interface RotationPattern {
  readonly segments: readonly RotationSegment[];
  readonly cycle: readonly DayRole[];
}

const ROLE_WORDS: Record<string, DayRole> = {
  on: 'onshift',
  onshift: 'onshift',
  off: 'offshift',
  offshift: 'offshift',
  travel: 'travel',
  t: 'travel',
  standby: 'standby',
  sb: 'standby',
};

const SEGMENT_PATTERN = /^(\d+)\s*([a-z]+)$/;
const SHORTHAND_PATTERN = /^(\d+)\/(\d+)$/;
const MAX_CYCLE_DAYS = 366;

/** The classic swing rota: two on, two off, three on, then the mirror of it. */
const PANAMA: readonly RotationSegment[] = [
  { role: 'onshift', days: 2 },
  { role: 'offshift', days: 2 },
  { role: 'onshift', days: 3 },
  { role: 'offshift', days: 2 },
  { role: 'onshift', days: 2 },
  { role: 'offshift', days: 3 },
];

function expand(segments: readonly RotationSegment[]): DayRole[] {
  const cycle: DayRole[] = [];
  for (const segment of segments) {
    for (let day = 0; day < segment.days; day += 1) {
      cycle.push(segment.role);
    }
  }
  return cycle;
}

function collapse(segments: readonly RotationSegment[]): RotationSegment[] {
  const collapsed: RotationSegment[] = [];
  for (const segment of segments) {
    const last = collapsed[collapsed.length - 1];
    if (last !== undefined && last.role === segment.role) {
      collapsed[collapsed.length - 1] = { role: last.role, days: last.days + segment.days };
      continue;
    }
    collapsed.push(segment);
  }
  return collapsed;
}

export function rotationPattern(segments: readonly RotationSegment[]): RotationPattern {
  if (segments.length === 0) {
    throw ValidationError.field('pattern', 'needs at least one segment');
  }
  for (const segment of segments) {
    if (!Number.isInteger(segment.days) || segment.days < 1) {
      throw ValidationError.field('pattern', `has a segment of ${segment.days} days`);
    }
  }
  const collapsed = collapse(segments);
  const cycle = expand(collapsed);
  if (cycle.length > MAX_CYCLE_DAYS) {
    throw ValidationError.field('pattern', `runs for ${cycle.length} days, longer than a year`);
  }
  if (!cycle.includes('onshift')) {
    throw ValidationError.field('pattern', 'has no on shift days at all');
  }
  return { segments: collapsed, cycle };
}

function parseSegment(token: string): RotationSegment {
  const match = SEGMENT_PATTERN.exec(token.trim().toLowerCase());
  if (match === null) {
    throw ValidationError.field('pattern', `cannot read the segment ${token}`);
  }
  const word = match[2] ?? '';
  const role = ROLE_WORDS[word];
  if (role === undefined) {
    throw ValidationError.field('pattern', `does not know the day role ${word}`);
  }
  return { role, days: Number(match[1]) };
}

/**
 * Reads the ways people actually write a rota down: "14/14" for a fortnight
 * each way, "2-2-3" for the swing pattern, or an explicit run of segments such
 * as "1travel 13on 1travel 13off".
 */
export function parseRotationPattern(text: string): RotationPattern {
  const trimmed = text.trim().toLowerCase();
  if (trimmed.length === 0) {
    throw ValidationError.field('pattern', 'is empty');
  }
  if (trimmed === '2-2-3') {
    return rotationPattern(PANAMA);
  }
  const shorthand = SHORTHAND_PATTERN.exec(trimmed);
  if (shorthand !== null) {
    return rotationPattern([
      { role: 'onshift', days: Number(shorthand[1]) },
      { role: 'offshift', days: Number(shorthand[2]) },
    ]);
  }
  const tokens = trimmed.split(/[\s,]+/).filter((token) => token.length > 0);
  return rotationPattern(tokens.map(parseSegment));
}

export function formatRotationPattern(pattern: RotationPattern): string {
  return pattern.segments.map((segment) => `${segment.days}${shortRole(segment.role)}`).join(' ');
}

function shortRole(role: DayRole): string {
  switch (role) {
    case 'onshift':
      return 'on';
    case 'offshift':
      return 'off';
    case 'travel':
      return 'travel';
    case 'standby':
      return 'standby';
  }
}

export function cycleLength(pattern: RotationPattern): number {
  return pattern.cycle.length;
}

export function roleAtIndex(pattern: RotationPattern, index: number): DayRole {
  const length = pattern.cycle.length;
  const wrapped = ((index % length) + length) % length;
  return pattern.cycle[wrapped] ?? 'offshift';
}

export function countRole(pattern: RotationPattern, role: DayRole): number {
  return pattern.cycle.filter((entry) => entry === role).length;
}

/** Share of the cycle spent on shift, which is what a crewing plan is costed on. */
export function dutyFraction(pattern: RotationPattern): number {
  return countRole(pattern, 'onshift') / cycleLength(pattern);
}

export function isDayRole(value: string): value is DayRole {
  return (DAY_ROLES as readonly string[]).includes(value);
}
