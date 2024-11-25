import { describe, expect, it } from 'vitest';
import {
  coveredRanges,
  describeShield,
  isShielded,
  shield,
  shieldOn,
  shieldedDayCount,
  shieldsFor,
  shieldsOver,
  type ShieldInput,
} from '../../../../src/domain/adherence/shield.js';
import { crewMemberId } from '../../../../src/domain/ids.js';
import { parseCivilDate } from '../../../../src/domain/time/civil.js';
import { formatDateRange, parseDateRange } from '../../../../src/domain/time/range.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';

const d = parseCivilDate;
const r = parseDateRange;
const ada = crewMemberId('mem_0123456789ABCDEF');
const bo = crewMemberId('mem_FEDCBA9876543210');
const grantedAt = parseInstant('2025-01-04T10:00:00Z');

function a(overrides: Partial<ShieldInput> = {}) {
  return shield({
    memberId: ada,
    range: r('2025-01-20..2025-01-26'),
    reason: 'annual_leave',
    grantedAt,
    ...overrides,
  });
}

describe('granting a shield', () => {
  it('lets a member declare leave or sickness themselves', () => {
    expect(a().grantedBy).toBeUndefined();
    expect(a({ reason: 'sick' }).grantedBy).toBeUndefined();
  });

  it('needs a supervisor for the other reasons', () => {
    expect(() => a({ reason: 'training' })).toThrow(/is needed to shield days for training/);
    expect(a({ reason: 'training', grantedBy: bo }).grantedBy).toBe(bo);
  });

  it('trims the note and bounds it', () => {
    expect(a({ note: '  two weeks off  ' }).note).toBe('two weeks off');
    expect(() => a({ note: 'x'.repeat(241) })).toThrow(/at most 240 characters/);
  });

  it('refuses a stretch longer than half a year', () => {
    expect(() => a({ range: r('2025-01-01..2025-12-31') })).toThrow(/more than the 180 allowed/);
  });
});

describe('finding shields', () => {
  const shields = [
    a(),
    a({ range: r('2025-03-03..2025-03-09'), reason: 'sick' }),
    a({ memberId: bo, range: r('2025-01-20..2025-01-26') }),
  ];

  it('filters to one member', () => {
    expect(shieldsFor(shields, ada)).toHaveLength(2);
    expect(shieldsFor(shields, bo)).toHaveLength(1);
  });

  it('finds the one covering a day', () => {
    expect(shieldOn(shields, ada, d('2025-01-22'))?.reason).toBe('annual_leave');
    expect(shieldOn(shields, ada, d('2025-01-27'))).toBeUndefined();
  });

  it('answers whether a day is shielded', () => {
    expect(isShielded(shields, ada, d('2025-01-20'))).toBe(true);
    expect(isShielded(shields, ada, d('2025-01-19'))).toBe(false);
    expect(isShielded(shields, bo, d('2025-03-05'))).toBe(false);
  });

  it('lists what touches a window', () => {
    expect(shieldsOver(shields, ada, r('2025-01-01..2025-01-31'))).toHaveLength(1);
    expect(shieldsOver(shields, ada, r('2025-01-01..2025-12-31'))).toHaveLength(2);
    expect(shieldsOver(shields, ada, r('2025-06-01..2025-06-30'))).toEqual([]);
  });
});

describe('counting shielded days', () => {
  it('counts the days inside a window', () => {
    expect(shieldedDayCount([a()], ada, r('2025-01-01..2025-01-31'))).toBe(7);
  });

  it('clips to the window', () => {
    expect(shieldedDayCount([a()], ada, r('2025-01-22..2025-01-31'))).toBe(5);
  });

  it('counts an overlapping pair once', () => {
    const overlapping = [a(), a({ range: r('2025-01-24..2025-01-30'), reason: 'sick' })];
    expect(shieldedDayCount(overlapping, ada, r('2025-01-01..2025-01-31'))).toBe(11);
  });

  it('counts nothing for a member with no shields', () => {
    expect(shieldedDayCount([a()], bo, r('2025-01-01..2025-01-31'))).toBe(0);
  });
});

describe('covered ranges', () => {
  it('merges the entries into runs', () => {
    const shields = [a(), a({ range: r('2025-01-27..2025-01-31'), reason: 'sick' })];
    expect(coveredRanges(shields, ada).map(formatDateRange)).toEqual(['2025-01-20..2025-01-31']);
  });

  it('keeps separate runs apart', () => {
    const shields = [a(), a({ range: r('2025-03-03..2025-03-09'), reason: 'sick' })];
    expect(coveredRanges(shields, ada)).toHaveLength(2);
  });
});

describe('describing a shield', () => {
  it('says who granted it', () => {
    expect(describeShield(a())).toBe('annual_leave self declared');
    expect(describeShield(a({ reason: 'training', grantedBy: bo }))).toBe(
      `training granted by ${bo}`,
    );
  });
});
