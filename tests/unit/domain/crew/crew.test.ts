import { describe, expect, it } from 'vitest';
import {
  checkOneCrewPerDay,
  crew,
  crewOf,
  endMembership,
  leadsOf,
  membersOn,
  membership,
  requireCrew,
  type CrewInput,
  type MembershipInput,
} from '../../../../src/domain/crew/crew.js';
import { crewId, crewMemberId } from '../../../../src/domain/ids.js';
import { formatCivilDate, parseCivilDate } from '../../../../src/domain/time/civil.js';
import { ConflictError, NotFoundError } from '../../../../src/domain/errors.js';

const d = parseCivilDate;
const alpha = crewId('crw_0123456789ABCDEF');
const bravo = crewId('crw_FEDCBA9876543210');
const ada = crewMemberId('mem_0123456789ABCDEF');
const bo = crewMemberId('mem_FEDCBA9876543210');

function aCrew(overrides: Partial<CrewInput> = {}) {
  return crew({
    id: alpha,
    name: 'Alpha production',
    site: 'Grane B',
    siteZone: 'Europe/Oslo',
    ...overrides,
  });
}

function aMembership(overrides: Partial<MembershipInput> = {}) {
  return membership({
    crewId: alpha,
    memberId: ada,
    from: d('2025-01-01'),
    ...overrides,
  });
}

describe('creating a crew', () => {
  it('tidies the name', () => {
    expect(aCrew({ name: '  Alpha   production ' }).name).toBe('Alpha production');
  });

  it('bounds the name', () => {
    expect(() => aCrew({ name: 'A' })).toThrow(/must be 2 to 60 characters/);
    expect(() => aCrew({ name: 'x'.repeat(61) })).toThrow(/must be 2 to 60 characters/);
  });

  it('checks the site name', () => {
    expect(() => aCrew({ site: '' })).toThrow(/not a usable site name/);
    expect(() => aCrew({ site: 'Grane/B' })).toThrow(/not a usable site name/);
    expect(aCrew({ site: 'Grane B' }).site).toBe('Grane B');
  });

  it('checks the zone', () => {
    expect(() => aCrew({ siteZone: 'North Sea/Grane' })).toThrow(/not a timezone/);
  });
});

describe('membership', () => {
  it('defaults to plain member with no end', () => {
    const entry = aMembership();
    expect(entry.role).toBe('member');
    expect(entry.to).toBeUndefined();
  });

  it('refuses an end before the start', () => {
    expect(() => aMembership({ to: d('2024-12-01') })).toThrow(/ends before the membership/);
  });

  it('ends on a date', () => {
    const ended = endMembership(aMembership(), d('2025-06-30'));
    expect(ended.to && formatCivilDate(ended.to)).toBe('2025-06-30');
  });

  it('refuses to end before it began', () => {
    expect(() => endMembership(aMembership(), d('2024-06-30'))).toThrow(/before the membership/);
  });
});

describe('reading a crew on a date', () => {
  const memberships = [
    aMembership({ to: d('2025-03-31') }),
    aMembership({ memberId: bo, from: d('2025-02-01'), role: 'lead' }),
    aMembership({ crewId: bravo, memberId: ada, from: d('2025-04-01') }),
  ];

  it('lists who was in it that day', () => {
    expect(membersOn(memberships, alpha, d('2025-01-15')).map((entry) => entry.memberId)).toEqual([
      ada,
    ]);
    expect(membersOn(memberships, alpha, d('2025-02-15'))).toHaveLength(2);
  });

  it('drops somebody once their membership ended', () => {
    expect(membersOn(memberships, alpha, d('2025-04-15')).map((entry) => entry.memberId)).toEqual([
      bo,
    ]);
  });

  it('finds the crew somebody was in', () => {
    expect(crewOf(memberships, ada, d('2025-02-01'))?.crewId).toBe(alpha);
    expect(crewOf(memberships, ada, d('2025-05-01'))?.crewId).toBe(bravo);
    expect(crewOf(memberships, ada, d('2024-01-01'))).toBeUndefined();
  });

  it('lists the leads', () => {
    expect(leadsOf(memberships, alpha, d('2025-02-15')).map((entry) => entry.memberId)).toEqual([
      bo,
    ]);
    expect(leadsOf(memberships, alpha, d('2025-01-15'))).toEqual([]);
  });
});

describe('one crew at a time', () => {
  it('accepts memberships that follow one another', () => {
    expect(() =>
      checkOneCrewPerDay([
        aMembership({ to: d('2025-03-31') }),
        aMembership({ crewId: bravo, from: d('2025-04-01') }),
      ]),
    ).not.toThrow();
  });

  it('refuses two crews on the same day', () => {
    expect(() =>
      checkOneCrewPerDay([
        aMembership({ to: d('2025-04-30') }),
        aMembership({ crewId: bravo, from: d('2025-04-01') }),
      ]),
    ).toThrow(ConflictError);
  });

  it('refuses two open ended memberships', () => {
    expect(() =>
      checkOneCrewPerDay([aMembership(), aMembership({ crewId: bravo, from: d('2026-01-01') })]),
    ).toThrow(ConflictError);
  });

  it('does not mind two people sharing a crew', () => {
    expect(() => checkOneCrewPerDay([aMembership(), aMembership({ memberId: bo })])).not.toThrow();
  });
});

describe('requireCrew', () => {
  it('finds one by id', () => {
    expect(requireCrew([aCrew()], alpha).name).toBe('Alpha production');
  });

  it('complains about one it does not hold', () => {
    expect(() => requireCrew([aCrew()], bravo)).toThrow(NotFoundError);
  });
});
