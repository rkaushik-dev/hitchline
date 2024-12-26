import { ValidationError } from '../domain/errors.js';
import { instant, parseInstant, type Instant } from '../domain/time/zone.js';
import type { Clock } from '../app/ports.js';

/**
 * Clocks. Nothing in the codebase calls `Date.now` directly, so every reading
 * of the current time comes through one of these and a test can pin it.
 */
export function systemClock(): Clock {
  return { now: () => instant(Date.now()) };
}

export interface MutableClock extends Clock {
  set(at: Instant): void;
  advance(milliseconds: number): void;
}

export function fixedClock(at: Instant | string): MutableClock {
  let current = typeof at === 'string' ? parseInstant(at) : at;
  return {
    now: () => current,
    set: (next: Instant) => {
      current = next;
    },
    advance: (milliseconds: number) => {
      if (!Number.isFinite(milliseconds)) {
        throw ValidationError.field('milliseconds', 'must be a finite number');
      }
      current = instant(current.epochMs + milliseconds);
    },
  };
}

/**
 * A clock that moves on by a fixed step every time it is read. Handy where a
 * sequence of events needs distinct timestamps without a test setting each one.
 */
export function tickingClock(start: Instant | string, stepMs = 1_000): Clock {
  if (!Number.isInteger(stepMs) || stepMs < 1) {
    throw ValidationError.field('stepMs', `must be a whole number above zero, got ${stepMs}`);
  }
  let current = typeof start === 'string' ? parseInstant(start) : start;
  return {
    now: () => {
      const reading = current;
      current = instant(current.epochMs + stepMs);
      return reading;
    },
  };
}

/** Reads a clock and rounds down to the second, which is what the api reports. */
export function nowToSecond(clock: Clock): Instant {
  return instant(Math.floor(clock.now().epochMs / 1000) * 1000);
}
