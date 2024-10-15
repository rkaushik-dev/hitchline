import { describe, expect, it } from 'vitest';
import {
  countRoleFor,
  dutyDays,
  effectiveBlocks,
  effectiveRoleOn,
  isFullyCovered,
  isOnShift,
  roleFor,
  roster,
  rolesFor,
} from '../../../../src/domain/rotation/roster.js';
import { rosterOverride } from '../../../../src/domain/rotation/override.js';
import { parseCivilDate } from '../../../../src/domain/time/civil.js';
import { formatDateRange, parseDateRange } from '../../../../src/domain/time/range.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';
import { aMember } from '../../../support/fixtures.js';

const d = parseCivilDate;
const r = parseDateRange;
const member = aMember();
const enteredAt = parseInstant('2025-01-18T10:00:00Z');

const heldOver = rosterOverride({
  memberId: member.id,
  range: r('2025-01-20..2025-01-22'),
  role: 'onshift',
  reason: 'held_over',
  note: 'relief flight cancelled',
  enteredAt,
});

describe('a roster with no overrides', () => {
  const plain = roster(member);

  it('reads straight through to the pattern', () => {
    expect(effectiveRoleOn(plain, d('2025-01-10'))).toEqual({ role: 'onshift', source: 'pattern' });
    expect(roleFor(plain, d('2025-01-25'))).toBe('offshift');
    expect(isOnShift(plain, d('2025-01-10'))).toBe(true);
  });

  it('counts duty days over a hitch', () => {
    expect(dutyDays(plain, r('2025-01-06..2025-02-02'))).toBe(14);
  });

  it('covers every day of a window', () => {
    expect(isFullyCovered(plain, r('2025-01-06..2025-02-02'))).toBe(true);
  });
});

describe('a roster with an override', () => {
  const held = roster(member, [heldOver]);

  it('reports the override as the source', () => {
    expect(effectiveRoleOn(held, d('2025-01-21'))).toEqual({
      role: 'onshift',
      source: 'override',
    });
  });

  it('leaves untouched days on the pattern', () => {
    expect(effectiveRoleOn(held, d('2025-01-23')).source).toBe('pattern');
    expect(roleFor(held, d('2025-01-23'))).toBe('offshift');
  });

  it('adds the held days to the duty count', () => {
    expect(dutyDays(held, r('2025-01-06..2025-02-02'))).toBe(17);
  });

  it('joins the held days onto the hitch as one block', () => {
    const blocks = effectiveBlocks(held, r('2025-01-06..2025-02-02'));
    expect(blocks.map((block) => `${block.role} ${formatDateRange(block.range)}`)).toEqual([
      'onshift 2025-01-06..2025-01-22',
      'offshift 2025-01-23..2025-02-02',
    ]);
  });

  it('lists a role for every day of the window', () => {
    expect(rolesFor(held, r('2025-01-19..2025-01-24'))).toEqual([
      'onshift',
      'onshift',
      'onshift',
      'onshift',
      'offshift',
      'offshift',
    ]);
  });
});

describe('an override that cuts a hitch short', () => {
  const sentHome = rosterOverride({
    memberId: member.id,
    range: r('2025-01-15..2025-01-19'),
    role: 'offshift',
    reason: 'medical',
    enteredAt,
  });
  const short = roster(member, [sentHome]);

  it('drops the days from the duty count', () => {
    expect(dutyDays(short, r('2025-01-06..2025-02-02'))).toBe(9);
  });

  it('splits the hitch into what was worked and what was not', () => {
    const blocks = effectiveBlocks(short, r('2025-01-06..2025-01-25'));
    expect(blocks.map((block) => `${block.role} ${formatDateRange(block.range)}`)).toEqual([
      'onshift 2025-01-06..2025-01-14',
      'offshift 2025-01-15..2025-01-25',
    ]);
  });

  it('counts the other roles too', () => {
    expect(countRoleFor(short, r('2025-01-06..2025-02-02'), 'offshift')).toBe(19);
    expect(countRoleFor(short, r('2025-01-06..2025-02-02'), 'travel')).toBe(0);
  });
});

describe('an override for somebody else', () => {
  it('is ignored', () => {
    const other = aMember({ id: 'mem_FEDCBA9876543210', displayName: 'Bo Lindqvist' });
    const mixed = roster(member, [
      rosterOverride({
        memberId: other.id,
        range: r('2025-01-20..2025-01-22'),
        role: 'onshift',
        reason: 'swapped',
        enteredAt,
      }),
    ]);
    expect(roleFor(mixed, d('2025-01-21'))).toBe('offshift');
  });
});
