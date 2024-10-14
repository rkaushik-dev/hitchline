import { describe, expect, it } from 'vitest';
import {
  checkNoClashes,
  describeOverride,
  firstDayOf,
  overrideCoversDay,
  overrideOn,
  overridesOver,
  rosterOverride,
  sortOverrides,
  type OverrideInput,
} from '../../../../src/domain/rotation/override.js';
import { crewMemberId } from '../../../../src/domain/ids.js';
import { parseCivilDate } from '../../../../src/domain/time/civil.js';
import { formatDateRange, parseDateRange } from '../../../../src/domain/time/range.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';
import { ConflictError } from '../../../../src/domain/errors.js';

const d = parseCivilDate;
const r = parseDateRange;
const ada = crewMemberId('mem_0123456789ABCDEF');
const bo = crewMemberId('mem_FEDCBA9876543210');
const enteredAt = parseInstant('2025-01-04T10:00:00Z');

function anOverride(overrides: Partial<OverrideInput> = {}) {
  return rosterOverride({
    memberId: ada,
    range: r('2025-01-20..2025-01-22'),
    role: 'onshift',
    reason: 'held_over',
    enteredAt,
    ...overrides,
  });
}

describe('creating an override', () => {
  it('keeps the reason and trims the note', () => {
    const entry = anOverride({ note: '  waiting on the relief crew  ' });
    expect(entry.reason).toBe('held_over');
    expect(entry.note).toBe('waiting on the relief crew');
  });

  it('defaults the note to empty', () => {
    expect(anOverride().note).toBe('');
  });

  it('refuses a note longer than the field allows', () => {
    expect(() => anOverride({ note: 'x'.repeat(241) })).toThrow(/at most 240 characters/);
  });

  it('refuses a run longer than four months', () => {
    expect(() => anOverride({ range: r('2025-01-01..2025-06-30') })).toThrow(/more than the 120/);
  });
});

describe('finding overrides', () => {
  const entries = [
    anOverride({ range: r('2025-02-10..2025-02-12'), reason: 'called_out' }),
    anOverride({ range: r('2025-01-20..2025-01-22') }),
    anOverride({ memberId: bo, range: r('2025-01-21..2025-01-23'), reason: 'swapped' }),
  ];

  it('sorts by the day they start', () => {
    expect(sortOverrides(entries).map((entry) => formatDateRange(entry.range))).toEqual([
      '2025-01-20..2025-01-22',
      '2025-01-21..2025-01-23',
      '2025-02-10..2025-02-12',
    ]);
  });

  it('finds the entry covering a day for one person', () => {
    const found = overrideOn(entries, ada, d('2025-01-21'));
    expect(found && formatDateRange(found.range)).toBe('2025-01-20..2025-01-22');
  });

  it('does not read another person entry', () => {
    expect(overrideOn(entries, ada, d('2025-01-23'))).toBeUndefined();
    const theirs = overrideOn(entries, bo, d('2025-01-23'));
    expect(theirs && theirs.reason).toBe('swapped');
  });

  it('lists what touches a window', () => {
    const touching = overridesOver(entries, ada, r('2025-01-01..2025-01-31'));
    expect(touching).toHaveLength(1);
    expect(overridesOver(entries, ada, r('2025-03-01..2025-03-31'))).toEqual([]);
  });
});

describe('clashes', () => {
  it('accepts entries that do not touch', () => {
    expect(() =>
      checkNoClashes([
        anOverride({ range: r('2025-01-20..2025-01-22') }),
        anOverride({ range: r('2025-01-23..2025-01-25') }),
      ]),
    ).not.toThrow();
  });

  it('rejects two entries covering the same day for one person', () => {
    expect(() =>
      checkNoClashes([
        anOverride({ range: r('2025-01-20..2025-01-22') }),
        anOverride({ range: r('2025-01-22..2025-01-25') }),
      ]),
    ).toThrow(ConflictError);
  });

  it('allows two people to be overridden on the same day', () => {
    expect(() =>
      checkNoClashes([
        anOverride({ range: r('2025-01-20..2025-01-22') }),
        anOverride({ memberId: bo, range: r('2025-01-20..2025-01-22') }),
      ]),
    ).not.toThrow();
  });
});

describe('reading an override', () => {
  it('says which days it covers', () => {
    const entry = anOverride();
    expect(overrideCoversDay(entry, d('2025-01-20'))).toBe(true);
    expect(overrideCoversDay(entry, d('2025-01-23'))).toBe(false);
    expect(firstDayOf(entry)).toBe('2025-01-20');
  });

  it('describes itself with the note', () => {
    const entry = anOverride({ note: 'relief crew weathered off' });
    expect(describeOverride(entry)).toBe(
      '2025-01-20..2025-01-22 onshift because held_over (relief crew weathered off)',
    );
  });

  it('describes itself without a note', () => {
    expect(describeOverride(anOverride())).toBe('2025-01-20..2025-01-22 onshift because held_over');
  });
});
