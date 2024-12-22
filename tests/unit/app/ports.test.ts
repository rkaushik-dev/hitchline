import { describe, expect, it } from 'vitest';
import { isDurable, type Clock, type IdSource, type Ports } from '../../../src/app/ports.js';
import { instant } from '../../../src/domain/time/zone.js';
import { mintId } from '../../../src/domain/ids.js';

describe('isDurable', () => {
  it('accepts something with both methods', () => {
    expect(isDurable({ flush: async () => {}, close: async () => {} })).toBe(true);
  });

  it('rejects something with only one', () => {
    expect(isDurable({ flush: async () => {} })).toBe(false);
  });

  it('rejects a plain value', () => {
    expect(isDurable(null)).toBe(false);
    expect(isDurable('store')).toBe(false);
    expect(isDurable(undefined)).toBe(false);
  });
});

describe('the port shapes', () => {
  it('lets a hand rolled clock stand in', () => {
    const clock: Clock = { now: () => instant(1_736_500_000_000) };
    expect(clock.now().epochMs).toBe(1_736_500_000_000);
  });

  it('lets a hand rolled id source stand in', () => {
    const ids: IdSource = { next: (kind) => mintId(kind, new Uint8Array(16)) };
    expect(ids.next('HabitId')).toBe('hab_0000000000000000');
    expect(ids.next('CrewId')).toBe('crw_0000000000000000');
  });

  it('collects the whole set behind one type', () => {
    const partial: Partial<Ports> = { clock: { now: () => instant(0) } };
    expect(partial.clock?.now().epochMs).toBe(0);
  });
});
