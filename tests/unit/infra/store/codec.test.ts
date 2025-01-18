import { describe, expect, it } from 'vitest';
import {
  decodeCheckIn,
  decodeCrew,
  decodeHabit,
  decodeMember,
  decodeMembership,
  decodeOverride,
  decodeSharing,
  decodeShield,
  encodeCheckIn,
  encodeCrew,
  encodeHabit,
  encodeMember,
  encodeMembership,
  encodeOverride,
  encodeSharing,
  encodeShield,
} from '../../../../src/infra/store/codec.js';
import { checkIn } from '../../../../src/domain/checkin/checkin.js';
import { crew, membership } from '../../../../src/domain/crew/crew.js';
import { moveTo } from '../../../../src/domain/crew/member.js';
import { setHabitVisibility, sharingPreference } from '../../../../src/domain/crew/sharing.js';
import { rosterOverride } from '../../../../src/domain/rotation/override.js';
import { shield } from '../../../../src/domain/adherence/shield.js';
import { checkInId, crewId, crewMemberId, habitId } from '../../../../src/domain/ids.js';
import { parseCivilDate } from '../../../../src/domain/time/civil.js';
import { parseDateRange } from '../../../../src/domain/time/range.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';
import { ValidationError } from '../../../../src/domain/errors.js';
import { aHabit, aMember } from '../../../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const bo = crewMemberId('mem_FEDCBA9876543210');
const treadmill = habitId('hab_0123456789ABCDEF');
const alpha = crewId('crw_0123456789ABCDEF');

describe('members', () => {
  it('round trips', () => {
    const member = aMember();
    expect(decodeMember(encodeMember(member))).toEqual(member);
  });

  it('keeps the rotation as a readable pattern', () => {
    expect(encodeMember(aMember())['pattern']).toBe('14on 14off');
  });

  it('keeps a suspended status', () => {
    const suspended = moveTo(aMember(), 'suspended');
    expect(decodeMember(encodeMember(suspended)).status).toBe('suspended');
  });

  it('refuses a row with a missing field', () => {
    const row = encodeMember(aMember());
    delete row['siteZone'];
    expect(() => decodeMember(row)).toThrow(ValidationError);
  });

  it('refuses a row with a field of the wrong type', () => {
    expect(() => decodeMember({ ...encodeMember(aMember()), phase: 'first' })).toThrow(
      /must be a number/,
    );
  });
});

describe('habits', () => {
  it('round trips without a target', () => {
    const entry = aHabit();
    expect(decodeHabit(encodeHabit(entry))).toEqual(entry);
  });

  it('round trips with a target and an end date', () => {
    const entry = aHabit({
      target: 'at least 20 minutes',
      endsOn: '2025-06-30',
      tags: ['fitness'],
    });
    const back = decodeHabit(encodeHabit(entry));
    expect(back.target?.amount).toBe(20);
    expect(back.endsOn?.month).toBe(6);
    expect(back.tags).toEqual(['fitness']);
  });

  it('writes an absent target as null', () => {
    expect(encodeHabit(aHabit())['target']).toBeNull();
  });

  it('refuses a tag list that is not strings', () => {
    expect(() => decodeHabit({ ...encodeHabit(aHabit()), tags: [1, 2] })).toThrow(
      /list of strings/,
    );
  });
});

describe('check ins', () => {
  const entry = checkIn({
    id: checkInId('chk_0123456789ABCDEF'),
    habitId: treadmill,
    memberId: ada,
    at: parseInstant('2025-01-10T07:30:00Z'),
    recordedAt: parseInstant('2025-01-10T07:31:00Z'),
    source: 'watch',
    reading: 22,
    note: 'before the shift',
    idempotencyKey: 'watch-2025-01-10',
  });

  it('round trips', () => {
    expect(decodeCheckIn(encodeCheckIn(entry))).toEqual(entry);
  });

  it('keeps a reading of zero rather than losing it', () => {
    const zero = { ...entry, reading: 0 };
    expect(decodeCheckIn(encodeCheckIn(zero)).reading).toBe(0);
  });

  it('round trips a voided entry', () => {
    const voided = { ...entry, voidedAt: parseInstant('2025-01-11T09:00:00Z') };
    expect(decodeCheckIn(encodeCheckIn(voided)).voidedAt?.epochMs).toBe(
      parseInstant('2025-01-11T09:00:00Z').epochMs,
    );
  });
});

describe('overrides and shields', () => {
  it('round trips an override', () => {
    const entry = rosterOverride({
      memberId: ada,
      range: parseDateRange('2025-01-20..2025-01-22'),
      role: 'onshift',
      reason: 'held_over',
      note: 'relief weathered off',
      enteredAt: parseInstant('2025-01-18T10:00:00Z'),
    });
    expect(decodeOverride(encodeOverride(entry))).toEqual(entry);
  });

  it('round trips a shield with a granter', () => {
    const entry = shield({
      memberId: ada,
      range: parseDateRange('2025-02-03..2025-02-07'),
      reason: 'training',
      grantedBy: bo,
      grantedAt: parseInstant('2025-01-18T10:00:00Z'),
    });
    expect(decodeShield(encodeShield(entry))).toEqual(entry);
  });

  it('round trips a self declared shield', () => {
    const entry = shield({
      memberId: ada,
      range: parseDateRange('2025-02-03..2025-02-07'),
      reason: 'sick',
      grantedAt: parseInstant('2025-01-18T10:00:00Z'),
    });
    expect(decodeShield(encodeShield(entry)).grantedBy).toBeUndefined();
  });
});

describe('crews', () => {
  it('round trips a crew', () => {
    const entry = crew({
      id: alpha,
      name: 'Alpha production',
      site: 'Grane B',
      siteZone: 'Europe/Oslo',
    });
    expect(decodeCrew(encodeCrew(entry))).toEqual(entry);
  });

  it('round trips an open ended membership', () => {
    const entry = membership({ crewId: alpha, memberId: ada, from: parseCivilDate('2025-01-01') });
    expect(decodeMembership(encodeMembership(entry))).toEqual(entry);
  });

  it('round trips a closed membership', () => {
    const entry = membership({
      crewId: alpha,
      memberId: ada,
      from: parseCivilDate('2025-01-01'),
      to: parseCivilDate('2025-06-30'),
      role: 'lead',
    });
    const back = decodeMembership(encodeMembership(entry));
    expect(back.to?.month).toBe(6);
    expect(back.role).toBe('lead');
  });
});

describe('sharing preferences', () => {
  it('round trips the fallback and the per habit map', () => {
    const preference = setHabitVisibility(
      sharingPreference({ memberId: ada, fallback: 'lead_only' }),
      treadmill,
      'crew',
    );
    const back = decodeSharing(encodeSharing(preference));
    expect(back.fallback).toBe('lead_only');
    expect(back.perHabit.get(treadmill)).toBe('crew');
  });

  it('refuses a malformed pair', () => {
    const row = { ...encodeSharing(sharingPreference({ memberId: ada })), perHabit: ['nonsense'] };
    expect(() => decodeSharing(row)).toThrow(/is not a pair/);
  });
});
