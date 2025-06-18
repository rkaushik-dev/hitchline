import { describe, expect, it } from 'vitest';
import { readiness, statusFor, worstOf } from '../../../src/http/readiness.js';
import { readConfig } from '../../../src/config/env.js';
import { memoryStore } from '../../../src/infra/store/memory.js';
import { fixedClock } from '../../../src/infra/clock.js';
import { instant } from '../../../src/domain/time/zone.js';
import type { Ports } from '../../../src/app/ports.js';
import { aMember } from '../../support/fixtures.js';

const config = { ...readConfig({}), store: 'memory' as const };

function ticking(steps: readonly number[]): () => number {
  let index = 0;
  return () => {
    const value = steps[Math.min(index, steps.length - 1)] ?? 0;
    index += 1;
    return value;
  };
}

describe('rolling up states', () => {
  it('takes the worst of them', () => {
    expect(worstOf([])).toBe('pass');
    expect(worstOf([{ name: 'a', state: 'pass', detail: '', ms: 0 }])).toBe('pass');
    expect(
      worstOf([
        { name: 'a', state: 'pass', detail: '', ms: 0 },
        { name: 'b', state: 'warn', detail: '', ms: 0 },
      ]),
    ).toBe('warn');
    expect(
      worstOf([
        { name: 'a', state: 'warn', detail: '', ms: 0 },
        { name: 'b', state: 'fail', detail: '', ms: 0 },
      ]),
    ).toBe('fail');
  });

  it('answers 503 only when something failed', () => {
    expect(statusFor({ state: 'pass', checks: [] })).toBe(200);
    expect(statusFor({ state: 'warn', checks: [] })).toBe(200);
    expect(statusFor({ state: 'fail', checks: [] })).toBe(503);
  });
});

describe('reading readiness', () => {
  it('passes on a healthy store', async () => {
    const ports = memoryStore({ clock: fixedClock('2025-06-19T09:00:00Z') });
    await ports.members.save(aMember());
    const report = await readiness({ ports, config, now: () => 0 });
    expect(report.state).toBe('pass');
    expect(report.checks.map((check) => check.name)).toEqual(['store', 'clock', 'durability']);
    expect(report.checks[0]?.detail).toBe('1 members');
  });

  it('warns when the store is slow', async () => {
    const ports = memoryStore({ clock: fixedClock('2025-06-19T09:00:00Z') });
    const report = await readiness({ ports, config, now: ticking([0, 400]), slowMs: 250 });
    expect(report.checks[0]?.state).toBe('warn');
    expect(report.state).toBe('warn');
    expect(statusFor(report)).toBe(200);
  });

  it('fails when the store throws', async () => {
    const broken = {
      ...memoryStore({ clock: fixedClock('2025-06-19T09:00:00Z') }),
      members: {
        byId: () => Promise.resolve(undefined),
        all: () => Promise.reject(new Error('disk went away')),
        save: () => Promise.resolve(),
      },
    } as unknown as Ports;
    const report = await readiness({ ports: broken, config, now: () => 0 });
    expect(report.state).toBe('fail');
    expect(report.checks[0]?.detail).toBe('disk went away');
    expect(statusFor(report)).toBe(503);
  });

  it('fails when the clock reads nothing', async () => {
    const ports = {
      ...memoryStore({ clock: fixedClock('2025-06-19T09:00:00Z') }),
      clock: { now: () => instant(0) },
    } as Ports;
    const broken = { ...ports, clock: { now: () => ({ epochMs: Number.NaN }) } } as Ports;
    const report = await readiness({ ports: broken, config, now: () => 0 });
    expect(report.checks[1]?.state).toBe('fail');
  });

  it('says which store it is running on', async () => {
    const ports = memoryStore({ clock: fixedClock('2025-06-19T09:00:00Z') });
    const memory = await readiness({ ports, config, now: () => 0 });
    expect(memory.checks[2]?.detail).toBe('memory only');

    const onDisk = await readiness({ ports, config: { ...config, store: 'file' }, now: () => 0 });
    expect(onDisk.checks[2]?.state).toBe('warn');
    expect(onDisk.checks[2]?.detail).toBe('journal on disk');
  });
});
