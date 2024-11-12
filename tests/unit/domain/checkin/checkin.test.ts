import { describe, expect, it } from 'vitest';
import {
  amendReading,
  checkIn,
  compareCheckIns,
  isBackfilled,
  isCheckInSource,
  isVoided,
  reportingDelayMinutes,
  voidCheckIn,
  type CheckInInput,
} from '../../../../src/domain/checkin/checkin.js';
import { checkInId, crewMemberId, habitId } from '../../../../src/domain/ids.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';

const at = parseInstant('2025-01-10T07:30:00Z');
const recordedAt = parseInstant('2025-01-10T07:31:00Z');

function a(overrides: Partial<CheckInInput> = {}) {
  return checkIn({
    id: checkInId('chk_0123456789ABCDEF'),
    habitId: habitId('hab_0123456789ABCDEF'),
    memberId: crewMemberId('mem_0123456789ABCDEF'),
    at,
    recordedAt,
    source: 'app',
    ...overrides,
  });
}

describe('sources', () => {
  it('knows the ones it accepts', () => {
    expect(isCheckInSource('kiosk')).toBe(true);
    expect(isCheckInSource('sms')).toBe(false);
  });
});

describe('creating a check in', () => {
  it('starts unvoided with a trimmed note', () => {
    const entry = a({ note: '  did it before breakfast  ' });
    expect(entry.note).toBe('did it before breakfast');
    expect(isVoided(entry)).toBe(false);
  });

  it('allows a reading of zero', () => {
    expect(a({ reading: 0 }).reading).toBe(0);
  });

  it('refuses a negative or absurd reading', () => {
    expect(() => a({ reading: -1 })).toThrow(/must not be negative/);
    expect(() => a({ reading: 100_001 })).toThrow(/at most 100000/);
  });

  it('refuses a note longer than the field', () => {
    expect(() => a({ note: 'x'.repeat(501) })).toThrow(/at most 500 characters/);
  });

  it('refuses a recording time well before the event', () => {
    expect(() => a({ recordedAt: parseInstant('2025-01-10T07:00:00Z') })).toThrow(
      /before the check in happened/,
    );
  });

  it('allows a minute of clock slack', () => {
    expect(() => a({ recordedAt: parseInstant('2025-01-10T07:29:30Z') })).not.toThrow();
  });

  it('checks the shape of an idempotency key', () => {
    expect(() => a({ idempotencyKey: 'short' })).toThrow(/8 to 64 plain characters/);
    expect(a({ idempotencyKey: 'watch-2025-01-10-0730' }).idempotencyKey).toBe(
      'watch-2025-01-10-0730',
    );
  });
});

describe('voiding', () => {
  it('records when it was voided', () => {
    const voided = voidCheckIn(a(), parseInstant('2025-01-11T09:00:00Z'));
    expect(isVoided(voided)).toBe(true);
    expect(voided.voidedAt?.epochMs).toBe(parseInstant('2025-01-11T09:00:00Z').epochMs);
  });

  it('leaves an already voided entry alone', () => {
    const voided = voidCheckIn(a(), parseInstant('2025-01-11T09:00:00Z'));
    expect(voidCheckIn(voided, parseInstant('2025-01-12T09:00:00Z'))).toBe(voided);
  });

  it('refuses to void before the entry was recorded', () => {
    expect(() => voidCheckIn(a(), parseInstant('2025-01-09T09:00:00Z'))).toThrow(
      /before it was recorded/,
    );
  });
});

describe('amending', () => {
  it('replaces the reading', () => {
    expect(amendReading(a({ reading: 20 }), 25).reading).toBe(25);
  });

  it('clears the reading', () => {
    expect(amendReading(a({ reading: 20 }), undefined).reading).toBeUndefined();
  });

  it('refuses a negative reading', () => {
    expect(() => amendReading(a(), -3)).toThrow(/must not be negative/);
  });

  it('refuses to amend a voided entry', () => {
    const voided = voidCheckIn(a(), parseInstant('2025-01-11T09:00:00Z'));
    expect(() => amendReading(voided, 25)).toThrow(/voided check in/);
  });
});

describe('reporting delay', () => {
  it('counts the minutes between doing and telling', () => {
    expect(reportingDelayMinutes(a())).toBe(1);
    expect(reportingDelayMinutes(a({ recordedAt: parseInstant('2025-01-12T07:30:00Z') }))).toBe(
      2880,
    );
  });

  it('never goes negative on a slack clock', () => {
    expect(reportingDelayMinutes(a({ recordedAt: parseInstant('2025-01-10T07:29:30Z') }))).toBe(0);
  });

  it('flags an entry that arrived more than a day late', () => {
    expect(isBackfilled(a())).toBe(false);
    expect(isBackfilled(a({ recordedAt: parseInstant('2025-01-12T07:30:00Z') }))).toBe(true);
  });

  it('takes a tighter threshold when asked', () => {
    expect(isBackfilled(a({ recordedAt: parseInstant('2025-01-10T09:30:00Z') }), 60)).toBe(true);
  });
});

describe('ordering', () => {
  it('sorts by the time it happened', () => {
    const early = a({ at: parseInstant('2025-01-10T06:00:00Z') });
    const late = a({
      at: parseInstant('2025-01-10T08:00:00Z'),
      recordedAt: parseInstant('2025-01-10T08:05:00Z'),
    });
    expect(compareCheckIns(early, late)).toBe(-1);
    expect(compareCheckIns(late, early)).toBe(1);
  });

  it('breaks a tie on the id', () => {
    const first = a();
    const second = a({ id: checkInId('chk_FEDCBA9876543210') });
    expect(compareCheckIns(first, second)).toBeLessThan(0);
    expect(compareCheckIns(first, first)).toBe(0);
  });
});
