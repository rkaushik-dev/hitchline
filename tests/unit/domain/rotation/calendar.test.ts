import { describe, expect, it } from 'vitest';
import {
  blocksIn,
  buildCalendar,
  countIn,
  describeCalendar,
  roleOnDay,
  roleThrough,
} from '../../../../src/domain/rotation/calendar.js';
import { roster } from '../../../../src/domain/rotation/roster.js';
import { rosterOverride } from '../../../../src/domain/rotation/override.js';
import { parseCivilDate } from '../../../../src/domain/time/civil.js';
import { formatDateRange, parseDateRange } from '../../../../src/domain/time/range.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';
import { aMember } from '../../../support/fixtures.js';

const d = parseCivilDate;
const r = parseDateRange;
const member = aMember();
const plain = roster(member);

describe('building one', () => {
  it('has a role for every day in the window', () => {
    const calendar = buildCalendar(plain, r('2025-01-06..2025-01-12'));
    expect(calendar.roles).toHaveLength(7);
    expect(calendar.roles.every((role) => role === 'onshift')).toBe(true);
  });

  it('reads a single day window', () => {
    expect(buildCalendar(plain, r('2025-01-06..2025-01-06')).roles).toEqual(['onshift']);
  });

  it('follows the overrides the roster carries', () => {
    const held = roster(member, [
      rosterOverride({
        memberId: member.id,
        range: r('2025-01-20..2025-01-22'),
        role: 'onshift',
        reason: 'held_over',
        enteredAt: parseInstant('2025-01-18T10:00:00Z'),
      }),
    ]);
    const calendar = buildCalendar(held, r('2025-01-19..2025-01-23'));
    expect(calendar.roles).toEqual(['onshift', 'onshift', 'onshift', 'onshift', 'offshift']);
  });
});

describe('reading a day', () => {
  const calendar = buildCalendar(plain, r('2025-01-06..2025-01-31'));

  it('answers from the array inside the window', () => {
    expect(roleOnDay(calendar, d('2025-01-10'))).toBe('onshift');
    expect(roleOnDay(calendar, d('2025-01-25'))).toBe('offshift');
  });

  it('answers nothing outside it', () => {
    expect(roleOnDay(calendar, d('2025-02-10'))).toBeUndefined();
  });

  it('falls through to the roster outside it', () => {
    expect(roleThrough(calendar, plain, d('2025-02-20'))).toBe('offshift');
    expect(roleThrough(calendar, plain, d('2025-02-05'))).toBe('onshift');
  });

  it('agrees with the roster everywhere inside it', () => {
    for (const role of calendar.roles.entries()) {
      expect(role).toBeDefined();
    }
    expect(countIn(calendar, 'onshift')).toBe(14);
    expect(countIn(calendar, 'offshift')).toBe(12);
  });
});

describe('blocks', () => {
  it('collects the runs of same role days', () => {
    const calendar = buildCalendar(plain, r('2025-01-06..2025-02-05'));
    const blocks = blocksIn(calendar);
    expect(blocks.map((block) => `${block.role} ${formatDateRange(block.range)}`)).toEqual([
      'onshift 2025-01-06..2025-01-19',
      'offshift 2025-01-20..2025-02-02',
      'onshift 2025-02-03..2025-02-05',
    ]);
  });
});

describe('describing one', () => {
  it('reads back as a window and a count', () => {
    expect(describeCalendar(buildCalendar(plain, r('2025-01-06..2025-01-12')))).toBe(
      '2025-01-06 to 2025-01-12, 7 days',
    );
  });
});
