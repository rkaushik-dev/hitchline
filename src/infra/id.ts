import { randomFillSync } from 'node:crypto';
import { ValidationError } from '../domain/errors.js';
import { mintId, type IdKind } from '../domain/ids.js';
import type { IdSource } from '../app/ports.js';

const BYTES = 16;

/**
 * Id sources. Both of these hand `mintId` a block of bytes and let the domain
 * decide what an id looks like, so the alphabet lives in one place.
 */
export function randomIds(): IdSource {
  return {
    next: (kind: IdKind) => mintId(kind, randomFillSync(new Uint8Array(BYTES))),
  };
}

/**
 * Deterministic ids for tests and fixtures. The counter is written across the
 * byte block so consecutive ids differ in more than the last character, which
 * makes a failing assertion easier to read.
 */
export function sequentialIds(seed = 0): IdSource {
  let counter = seed;
  return {
    next: (kind: IdKind) => {
      counter += 1;
      const bytes = new Uint8Array(BYTES);
      for (let index = 0; index < BYTES; index += 1) {
        bytes[index] = (counter >> ((index % 4) * 8)) & 0xff;
      }
      return mintId(kind, bytes);
    },
  };
}

/** Hands back ids from a list, then refuses. Used to pin an id in a test. */
export function scriptedIds(values: readonly string[]): IdSource {
  let index = 0;
  return {
    next: () => {
      const value = values[index];
      if (value === undefined) {
        throw ValidationError.field('ids', `ran out after ${values.length} of them`);
      }
      index += 1;
      return value;
    },
  };
}
