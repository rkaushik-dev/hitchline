import { ValidationError } from '../errors.js';

/**
 * What counts as having done the thing. A habit with no target is satisfied by
 * turning up; one with a target needs the readings to add up.
 */
export type TargetUnit = 'count' | 'minutes' | 'kilometres' | 'litres' | 'grams' | 'pages';

export type TargetComparison = 'at_least' | 'at_most';

export interface HabitTarget {
  readonly unit: TargetUnit;
  readonly amount: number;
  readonly comparison: TargetComparison;
}

const UNITS: readonly TargetUnit[] = ['count', 'minutes', 'kilometres', 'litres', 'grams', 'pages'];

/** How many decimal places a reading in this unit is kept to. */
const PRECISION: Record<TargetUnit, number> = {
  count: 0,
  minutes: 0,
  kilometres: 2,
  litres: 2,
  grams: 0,
  pages: 0,
};

const ALIASES: Record<string, TargetUnit> = {
  count: 'count',
  times: 'count',
  min: 'minutes',
  mins: 'minutes',
  minutes: 'minutes',
  km: 'kilometres',
  kilometres: 'kilometres',
  l: 'litres',
  litres: 'litres',
  g: 'grams',
  grams: 'grams',
  pages: 'pages',
};

const MAX_AMOUNT = 100_000;
const TARGET_PATTERN = /^(at least|at most)\s+([0-9]+(?:\.[0-9]+)?)\s*([a-z]+)$/;

export function isTargetUnit(value: string): value is TargetUnit {
  return (UNITS as readonly string[]).includes(value);
}

export function unitFor(word: string): TargetUnit {
  const unit = ALIASES[word.trim().toLowerCase()];
  if (unit === undefined) {
    throw ValidationError.field('unit', `is not a unit this tracks: ${word}`);
  }
  return unit;
}

export function roundToUnit(unit: TargetUnit, amount: number): number {
  const places = PRECISION[unit];
  if (places === 0) return Math.round(amount);
  // Shifting through the decimal exponent rather than multiplying keeps
  // readings such as 1.005 litres off the wrong side of the rounding.
  const shifted = Math.round(Number(`${amount}e${places}`));
  return Number(`${shifted}e-${places}`);
}

export function habitTarget(
  unit: TargetUnit,
  amount: number,
  comparison: TargetComparison = 'at_least',
): HabitTarget {
  if (!Number.isFinite(amount) || amount <= 0) {
    throw ValidationError.field('amount', `must be a positive number, got ${amount}`);
  }
  if (amount > MAX_AMOUNT) {
    throw ValidationError.field('amount', `must be at most ${MAX_AMOUNT}, got ${amount}`);
  }
  const rounded = roundToUnit(unit, amount);
  if (rounded <= 0) {
    throw ValidationError.field('amount', `rounds to nothing in ${unit}`);
  }
  return { unit, amount: rounded, comparison };
}

/** Reads "at least 30 minutes" and "at most 2 litres". */
export function parseTarget(text: string): HabitTarget {
  const match = TARGET_PATTERN.exec(text.trim().toLowerCase());
  if (match === null) {
    throw ValidationError.field('target', `must look like at least 30 minutes, got ${text}`);
  }
  const comparison: TargetComparison = match[1] === 'at most' ? 'at_most' : 'at_least';
  return habitTarget(unitFor(match[3] ?? ''), Number(match[2]), comparison);
}

export function formatTarget(target: HabitTarget): string {
  const words = target.comparison === 'at_most' ? 'at most' : 'at least';
  return `${words} ${target.amount} ${target.unit}`;
}

export function meetsTarget(target: HabitTarget, total: number): boolean {
  const rounded = roundToUnit(target.unit, total);
  return target.comparison === 'at_least' ? rounded >= target.amount : rounded <= target.amount;
}

/**
 * How far along a total is, capped at one. An at most target reads as fully met
 * until it is breached, at which point it drops to zero.
 */
export function targetProgress(target: HabitTarget, total: number): number {
  const rounded = roundToUnit(target.unit, total);
  if (target.comparison === 'at_most') {
    return rounded <= target.amount ? 1 : 0;
  }
  if (target.amount === 0) return 1;
  return Math.min(1, Math.max(0, rounded / target.amount));
}

export function sumReadings(unit: TargetUnit, readings: readonly number[]): number {
  return roundToUnit(
    unit,
    readings.reduce((total, reading) => total + reading, 0),
  );
}
