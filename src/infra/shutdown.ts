import { ValidationError } from '../domain/errors.js';

/**
 * Shutting down without losing writes. Each task is run once, in the order it
 * was registered, and a task that throws does not stop the ones behind it,
 * because closing the journal matters more than any one of them succeeding.
 */
export type ShutdownTask = () => Promise<void> | void;

export interface ShutdownReport {
  readonly ran: number;
  readonly failed: readonly string[];
}

export interface Shutdown {
  add(name: string, task: ShutdownTask): void;
  run(): Promise<ShutdownReport>;
  readonly started: boolean;
  readonly size: number;
}

export function createShutdown(): Shutdown {
  const tasks: { name: string; task: ShutdownTask }[] = [];
  let started = false;
  let running: Promise<ShutdownReport> | undefined;

  return {
    add(name: string, task: ShutdownTask): void {
      if (name.trim().length === 0) {
        throw ValidationError.field('name', 'must not be empty');
      }
      if (started) {
        throw ValidationError.field('task', `${name} was added while shutting down`);
      }
      tasks.push({ name, task });
    },

    run(): Promise<ShutdownReport> {
      if (running !== undefined) return running;
      started = true;
      running = (async () => {
        const failed: string[] = [];
        for (const entry of tasks) {
          try {
            await entry.task();
          } catch {
            failed.push(entry.name);
          }
        }
        return { ran: tasks.length, failed };
      })();
      return running;
    },

    get started(): boolean {
      return started;
    },

    get size(): number {
      return tasks.length;
    },
  };
}

export type SignalName = 'SIGINT' | 'SIGTERM';

export interface SignalTarget {
  once(signal: SignalName, listener: () => void): unknown;
}

/** Wires the usual two signals to one shutdown, and answers how many it wired. */
export function onSignals(
  target: SignalTarget,
  shutdown: Shutdown,
  after: (report: ShutdownReport) => void,
): number {
  const signals: SignalName[] = ['SIGINT', 'SIGTERM'];
  for (const signal of signals) {
    target.once(signal, () => {
      void shutdown.run().then(after);
    });
  }
  return signals.length;
}
