/**
 * A tiny deterministic generator for the invariant tests. Bringing a property
 * testing library in would mean a shrinker and a runner we do not otherwise
 * need; a seeded sequence and a loop find the same class of bug and the
 * failing case is printed with its seed so it can be replayed.
 */
export interface Source {
  next(): number;
  int(lowest: number, highest: number): number;
  pick<T>(items: readonly T[]): T;
  chance(probability: number): boolean;
}

export function source(seed: number): Source {
  let state = seed >>> 0 || 1;
  const next = (): number => {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    state >>>= 0;
    return state / 0xffff_ffff;
  };
  return {
    next,
    int: (lowest: number, highest: number) => lowest + Math.floor(next() * (highest - lowest + 1)),
    pick: <T>(items: readonly T[]): T => {
      const chosen = items[Math.floor(next() * items.length)];
      if (chosen === undefined) throw new Error('cannot pick from an empty list');
      return chosen;
    },
    chance: (probability: number) => next() < probability,
  };
}

/** Runs a body once per seed, so a failure names the seed that produced it. */
export function forEachSeed(count: number, body: (source: Source, seed: number) => void): void {
  for (let seed = 1; seed <= count; seed += 1) {
    try {
      body(source(seed * 7919), seed);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      throw new Error(`seed ${String(seed)}: ${message}`);
    }
  }
}
