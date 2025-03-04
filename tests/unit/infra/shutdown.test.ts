import { describe, expect, it, vi } from 'vitest';
import {
  createShutdown,
  onSignals,
  type SignalName,
  type ShutdownReport,
} from '../../../src/infra/shutdown.js';
import { ValidationError } from '../../../src/domain/errors.js';

describe('registering tasks', () => {
  it('counts what it holds', () => {
    const shutdown = createShutdown();
    shutdown.add('http', () => undefined);
    shutdown.add('store', () => undefined);
    expect(shutdown.size).toBe(2);
    expect(shutdown.started).toBe(false);
  });

  it('refuses an empty name', () => {
    expect(() => createShutdown().add('  ', () => undefined)).toThrow(ValidationError);
  });

  it('refuses a task added once it has started', async () => {
    const shutdown = createShutdown();
    await shutdown.run();
    expect(() => shutdown.add('late', () => undefined)).toThrow(/while shutting down/);
  });
});

describe('running', () => {
  it('runs the tasks in order', async () => {
    const order: string[] = [];
    const shutdown = createShutdown();
    shutdown.add('first', () => {
      order.push('first');
    });
    shutdown.add('second', async () => {
      await Promise.resolve();
      order.push('second');
    });
    const report = await shutdown.run();
    expect(order).toEqual(['first', 'second']);
    expect(report).toEqual({ ran: 2, failed: [] });
  });

  it('carries on past a task that throws', async () => {
    const later = vi.fn();
    const shutdown = createShutdown();
    shutdown.add('broken', () => {
      throw new Error('no');
    });
    shutdown.add('store', later);
    const report = await shutdown.run();
    expect(report.failed).toEqual(['broken']);
    expect(later).toHaveBeenCalledOnce();
  });

  it('names a rejected promise as failed', async () => {
    const shutdown = createShutdown();
    shutdown.add('async', () => Promise.reject(new Error('no')));
    expect((await shutdown.run()).failed).toEqual(['async']);
  });

  it('runs once however many times it is called', async () => {
    const task = vi.fn();
    const shutdown = createShutdown();
    shutdown.add('once', task);
    await Promise.all([shutdown.run(), shutdown.run(), shutdown.run()]);
    expect(task).toHaveBeenCalledOnce();
  });

  it('runs nothing when nothing was registered', async () => {
    expect(await createShutdown().run()).toEqual({ ran: 0, failed: [] });
  });
});

describe('signals', () => {
  it('wires both of them', async () => {
    const listeners = new Map<SignalName, () => void>();
    const target = {
      once(signal: SignalName, listener: () => void) {
        listeners.set(signal, listener);
        return this;
      },
    };
    const task = vi.fn();
    const shutdown = createShutdown();
    shutdown.add('store', task);

    let seen: ShutdownReport | undefined;
    const wired = onSignals(target, shutdown, (report) => {
      seen = report;
    });
    expect(wired).toBe(2);
    expect([...listeners.keys()].sort()).toEqual(['SIGINT', 'SIGTERM']);

    listeners.get('SIGTERM')?.();
    await shutdown.run();
    expect(task).toHaveBeenCalledOnce();
    expect(seen?.ran).toBe(1);
  });
});
