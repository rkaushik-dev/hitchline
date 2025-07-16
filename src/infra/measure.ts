import { performance } from 'node:perf_hooks';
import { ValidationError } from '../domain/errors.js';

/**
 * A stopwatch for the two paths that grow with a crew: expanding a year of
 * obligations and scoring them. There is no benchmark framework here because
 * the answer wanted is "did this get slower", and a median over a few runs
 * answers that well enough to put in a commit message.
 */
export interface Timing {
  readonly name: string;
  readonly runs: number;
  readonly medianMs: number;
  readonly worstMs: number;
  readonly totalMs: number;
}

export interface MeasureOptions {
  readonly runs?: number;
  readonly warmup?: number;
  readonly now?: () => number;
}

export function measure(name: string, body: () => void, options: MeasureOptions = {}): Timing {
  const runs = options.runs ?? 5;
  const warmup = options.warmup ?? 1;
  if (!Number.isInteger(runs) || runs < 1) {
    throw ValidationError.field('runs', `must be at least one, got ${runs}`);
  }
  const now = options.now ?? (() => performance.now());

  for (let index = 0; index < warmup; index += 1) body();

  const timings: number[] = [];
  for (let index = 0; index < runs; index += 1) {
    const started = now();
    body();
    timings.push(now() - started);
  }
  const sorted = [...timings].sort((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);
  const median =
    sorted.length % 2 === 1
      ? (sorted[middle] ?? 0)
      : ((sorted[middle - 1] ?? 0) + (sorted[middle] ?? 0)) / 2;

  return {
    name,
    runs,
    medianMs: Math.round(median * 1000) / 1000,
    worstMs: Math.round((sorted[sorted.length - 1] ?? 0) * 1000) / 1000,
    totalMs: Math.round(timings.reduce((sum, value) => sum + value, 0) * 1000) / 1000,
  };
}

export function describeTiming(timing: Timing): string {
  return `${timing.name}: ${String(timing.medianMs)}ms median over ${String(timing.runs)} runs, worst ${String(timing.worstMs)}ms`;
}

/** True when a reading is slower than the budget it was given. */
export function isSlowerThan(timing: Timing, budgetMs: number): boolean {
  return timing.medianMs > budgetMs;
}
