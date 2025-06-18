import type { Ports } from '../app/ports.js';
import type { Config } from '../config/env.js';
import { isDurable } from '../app/ports.js';

/**
 * Two different questions. Liveness asks whether the process is worth keeping;
 * readiness asks whether it can serve a request right now. A container that
 * conflates them gets restarted every time the disk is slow.
 */
export type CheckState = 'pass' | 'warn' | 'fail';

export interface CheckResult {
  readonly name: string;
  readonly state: CheckState;
  readonly detail: string;
  readonly ms: number;
}

export interface Readiness {
  readonly state: CheckState;
  readonly checks: readonly CheckResult[];
}

export function worstOf(checks: readonly CheckResult[]): CheckState {
  if (checks.some((check) => check.state === 'fail')) return 'fail';
  if (checks.some((check) => check.state === 'warn')) return 'warn';
  return 'pass';
}

export function statusFor(readiness: Readiness): number {
  return readiness.state === 'fail' ? 503 : 200;
}

async function timed(
  name: string,
  now: () => number,
  body: () => Promise<string>,
): Promise<CheckResult> {
  const started = now();
  try {
    const detail = await body();
    return { name, state: 'pass', detail, ms: Math.round(now() - started) };
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    return { name, state: 'fail', detail, ms: Math.round(now() - started) };
  }
}

export interface ReadinessOptions {
  readonly ports: Ports;
  readonly config: Config;
  readonly now?: () => number;
  /** A store read slower than this is reported as a warning. */
  readonly slowMs?: number;
}

const DEFAULT_SLOW_MS = 250;

export async function readiness(options: ReadinessOptions): Promise<Readiness> {
  const now = options.now ?? (() => performance.now());
  const slowMs = options.slowMs ?? DEFAULT_SLOW_MS;

  const checks: CheckResult[] = [];

  const store = await timed('store', now, async () => {
    const members = await options.ports.members.all();
    return `${String(members.length)} members`;
  });
  checks.push(store.state === 'pass' && store.ms > slowMs ? { ...store, state: 'warn' } : store);

  checks.push(
    await timed('clock', now, () => {
      const reading = options.ports.clock.now();
      if (!Number.isFinite(reading.epochMs)) throw new Error('the clock read nothing');
      return Promise.resolve(new Date(reading.epochMs).toISOString());
    }),
  );

  checks.push({
    name: 'durability',
    state: options.config.store === 'file' && !isDurable(options.ports) ? 'warn' : 'pass',
    detail: options.config.store === 'file' ? 'journal on disk' : 'memory only',
    ms: 0,
  });

  return { state: worstOf(checks), checks };
}
