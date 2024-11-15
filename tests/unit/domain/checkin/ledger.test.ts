import { describe, expect, it } from 'vitest';
import {
  amendEntry,
  append,
  appendAll,
  emptyLedger,
  entriesBetween,
  entriesForHabit,
  findByKey,
  habitsTouched,
  lastEntry,
  ledgerOf,
  liveEntries,
  size,
  voidEntry,
} from '../../../../src/domain/checkin/ledger.js';
import { checkIn, type CheckIn } from '../../../../src/domain/checkin/checkin.js';
import { checkInId, crewMemberId, habitId } from '../../../../src/domain/ids.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';
import { ConflictError, NotFoundError } from '../../../../src/domain/errors.js';

const treadmill = habitId('hab_0123456789ABCDEF');
const stretches = habitId('hab_FEDCBA9876543210');
const ada = crewMemberId('mem_0123456789ABCDEF');

interface EntryOptions {
  readonly id?: string;
  readonly habit?: typeof treadmill;
  readonly at?: string;
  readonly key?: string;
  readonly reading?: number;
}

function entry(options: EntryOptions = {}): CheckIn {
  const at = parseInstant(options.at ?? '2025-01-10T07:30:00Z');
  return checkIn({
    id: checkInId(options.id ?? 'chk_0123456789ABCDEF'),
    habitId: options.habit ?? treadmill,
    memberId: ada,
    at,
    recordedAt: parseInstant(options.at ?? '2025-01-10T07:30:00Z'),
    source: 'app',
    reading: options.reading,
    idempotencyKey: options.key,
  });
}

describe('an empty ledger', () => {
  it('holds nothing', () => {
    const ledger = emptyLedger();
    expect(size(ledger)).toBe(0);
    expect(liveEntries(ledger)).toEqual([]);
    expect(lastEntry(ledger)).toBeUndefined();
  });
});

describe('appending', () => {
  it('accepts a new entry', () => {
    const result = append(emptyLedger(), entry());
    expect(result.accepted).toBe(true);
    expect(size(result.ledger)).toBe(1);
  });

  it('refuses the same id twice', () => {
    const once = append(emptyLedger(), entry()).ledger;
    expect(() => append(once, entry())).toThrow(ConflictError);
  });

  it('keeps entries in the order they happened', () => {
    const ledger = appendAll(emptyLedger(), [
      entry({ id: 'chk_0000000000000002', at: '2025-01-10T18:00:00Z' }),
      entry({ id: 'chk_0000000000000001', at: '2025-01-10T06:00:00Z' }),
    ]);
    expect(ledger.entries.map((item) => item.id)).toEqual([
      'chk_0000000000000001',
      'chk_0000000000000002',
    ]);
  });

  it('sorts a ledger built from a list', () => {
    const ledger = ledgerOf([
      entry({ id: 'chk_0000000000000002', at: '2025-01-10T18:00:00Z' }),
      entry({ id: 'chk_0000000000000001', at: '2025-01-10T06:00:00Z' }),
    ]);
    expect(lastEntry(ledger)?.id).toBe('chk_0000000000000002');
  });
});

describe('idempotency', () => {
  it('returns the entry already stored under the key', () => {
    const first = append(emptyLedger(), entry({ key: 'watch-2025-01-10' }));
    const second = append(
      first.ledger,
      entry({ id: 'chk_0000000000000009', key: 'watch-2025-01-10' }),
    );
    expect(second.accepted).toBe(false);
    expect(second.entry.id).toBe('chk_0123456789ABCDEF');
    expect(size(second.ledger)).toBe(1);
  });

  it('scopes the key to one habit', () => {
    const first = append(emptyLedger(), entry({ key: 'watch-2025-01-10' }));
    const second = append(
      first.ledger,
      entry({ id: 'chk_0000000000000009', habit: stretches, key: 'watch-2025-01-10' }),
    );
    expect(second.accepted).toBe(true);
    expect(size(second.ledger)).toBe(2);
  });

  it('finds an entry by its key', () => {
    const ledger = append(emptyLedger(), entry({ key: 'watch-2025-01-10' })).ledger;
    expect(findByKey(ledger, treadmill, 'watch-2025-01-10')?.id).toBe('chk_0123456789ABCDEF');
    expect(findByKey(ledger, treadmill, 'nothing-here')).toBeUndefined();
  });
});

describe('voiding and amending', () => {
  const ledger = append(emptyLedger(), entry({ reading: 20 })).ledger;
  const at = parseInstant('2025-01-11T09:00:00Z');

  it('keeps a voided entry but drops it from the live list', () => {
    const voided = voidEntry(ledger, checkInId('chk_0123456789ABCDEF'), at);
    expect(size(voided)).toBe(1);
    expect(liveEntries(voided)).toEqual([]);
  });

  it('amends a reading in place', () => {
    const amended = amendEntry(ledger, checkInId('chk_0123456789ABCDEF'), 25);
    expect(liveEntries(amended)[0]?.reading).toBe(25);
  });

  it('complains about an id it does not hold', () => {
    expect(() => voidEntry(ledger, checkInId('chk_9999999999999999'), at)).toThrow(NotFoundError);
  });

  it('leaves the original ledger alone', () => {
    voidEntry(ledger, checkInId('chk_0123456789ABCDEF'), at);
    expect(liveEntries(ledger)).toHaveLength(1);
  });
});

describe('reading back', () => {
  const ledger = appendAll(emptyLedger(), [
    entry({ id: 'chk_0000000000000001', at: '2025-01-10T06:00:00Z' }),
    entry({ id: 'chk_0000000000000002', at: '2025-01-10T18:00:00Z', habit: stretches }),
    entry({ id: 'chk_0000000000000003', at: '2025-01-12T06:00:00Z' }),
  ]);

  it('filters to one habit', () => {
    expect(entriesForHabit(ledger, treadmill).map((item) => item.id)).toEqual([
      'chk_0000000000000001',
      'chk_0000000000000003',
    ]);
  });

  it('lists the habits it has heard about', () => {
    expect(habitsTouched(ledger)).toEqual([treadmill, stretches].sort());
  });

  it('takes a half open interval', () => {
    const found = entriesBetween(
      ledger,
      parseInstant('2025-01-10T06:00:00Z'),
      parseInstant('2025-01-12T06:00:00Z'),
    );
    expect(found.map((item) => item.id)).toEqual(['chk_0000000000000001', 'chk_0000000000000002']);
  });

  it('leaves voided entries out of a range read', () => {
    const voided = voidEntry(
      ledger,
      checkInId('chk_0000000000000001'),
      parseInstant('2025-01-13T09:00:00Z'),
    );
    const found = entriesBetween(
      voided,
      parseInstant('2025-01-10T00:00:00Z'),
      parseInstant('2025-01-11T00:00:00Z'),
    );
    expect(found.map((item) => item.id)).toEqual(['chk_0000000000000002']);
  });
});
