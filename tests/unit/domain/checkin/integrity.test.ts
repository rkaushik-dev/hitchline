import { describe, expect, it } from 'vitest';
import {
  GENESIS,
  chainOf,
  describeCheck,
  digestOf,
  headOf,
  verifyChain,
} from '../../../../src/domain/checkin/integrity.js';
import { checkIn, voidCheckIn, type CheckIn } from '../../../../src/domain/checkin/checkin.js';
import { checkInId, crewMemberId, habitId } from '../../../../src/domain/ids.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';
import { ValidationError } from '../../../../src/domain/errors.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const treadmill = habitId('hab_0123456789ABCDEF');

function entry(id: string, at: string, reading?: number): CheckIn {
  return checkIn({
    id: checkInId(id),
    habitId: treadmill,
    memberId: ada,
    at: parseInstant(at),
    recordedAt: parseInstant(at),
    source: 'app',
    reading,
  });
}

const first = entry('chk_0000000000000001', '2025-01-10T07:00:00Z', 20);
const second = entry('chk_0000000000000002', '2025-01-11T07:00:00Z', 25);
const third = entry('chk_0000000000000003', '2025-01-12T07:00:00Z');

describe('digests', () => {
  it('is a lower case sha256 in hex', () => {
    expect(digestOf(first, GENESIS)).toMatch(/^[0-9a-f]{64}$/);
  });

  it('is stable for the same entry and predecessor', () => {
    expect(digestOf(first, GENESIS)).toBe(digestOf(first, GENESIS));
  });

  it('changes when any field changes', () => {
    const base = digestOf(first, GENESIS);
    expect(digestOf({ ...first, reading: 21 }, GENESIS)).not.toBe(base);
    expect(digestOf({ ...first, note: 'edited' }, GENESIS)).not.toBe(base);
    expect(digestOf({ ...first, source: 'kiosk' }, GENESIS)).not.toBe(base);
  });

  it('changes when the predecessor changes', () => {
    expect(digestOf(second, digestOf(first, GENESIS))).not.toBe(digestOf(second, GENESIS));
  });

  it('refuses a predecessor that is not a digest', () => {
    expect(() => digestOf(first, 'nope')).toThrow(ValidationError);
    const upper = digestOf(first, GENESIS).toUpperCase();
    expect(() => digestOf(first, upper)).toThrow(/lower case hex/);
  });
});

describe('the chain', () => {
  it('links every entry in event order', () => {
    const links = chainOf([third, first, second]);
    expect(links.map((link) => link.entry.id)).toEqual([
      'chk_0000000000000001',
      'chk_0000000000000002',
      'chk_0000000000000003',
    ]);
  });

  it('folds each digest onto the one before it', () => {
    const links = chainOf([first, second]);
    expect(links[0]?.digest).toBe(digestOf(first, GENESIS));
    expect(links[1]?.digest).toBe(digestOf(second, links[0]?.digest ?? GENESIS));
  });

  it('reads an empty ledger as the genesis digest', () => {
    expect(chainOf([])).toEqual([]);
    expect(headOf([])).toBe(GENESIS);
  });

  it('answers the last digest as the head', () => {
    const links = chainOf([first, second]);
    expect(headOf([first, second])).toBe(links[1]?.digest);
  });
});

describe('verifying', () => {
  const entries = [first, second, third];
  const recorded = chainOf(entries).map((link) => link.digest);

  it('passes on an untouched ledger', () => {
    const check = verifyChain(entries, recorded);
    expect(check.ok).toBe(true);
    expect(check.checked).toBe(3);
    expect(describeCheck(check)).toBe('3 entries verified');
  });

  it('names the first entry that was edited', () => {
    const edited = [first, { ...second, reading: 99 }, third];
    const check = verifyChain(edited, recorded);
    expect(check.ok).toBe(false);
    expect(check.brokenAt).toBe('chk_0000000000000002');
    expect(check.checked).toBe(2);
  });

  it('spots an entry that was removed', () => {
    const check = verifyChain([first, second], recorded);
    expect(check.ok).toBe(false);
  });

  it('spots an entry that was added', () => {
    const extra = entry('chk_0000000000000004', '2025-01-13T07:00:00Z');
    const check = verifyChain([...entries, extra], recorded);
    expect(check.ok).toBe(false);
    expect(check.brokenAt).toBe('chk_0000000000000004');
  });

  it('spots a void applied after the fact', () => {
    const voided = voidCheckIn(second, parseInstant('2025-01-20T09:00:00Z'));
    const check = verifyChain([first, voided, third], recorded);
    expect(check.ok).toBe(false);
    expect(check.brokenAt).toBe('chk_0000000000000002');
  });

  it('describes a break', () => {
    const check = verifyChain([first, { ...second, note: 'edited' }, third], recorded);
    expect(describeCheck(check)).toBe('broke at chk_0000000000000002 after 2 entries');
  });
});
