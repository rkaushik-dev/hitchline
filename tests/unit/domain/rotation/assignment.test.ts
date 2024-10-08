import { describe, expect, it } from 'vitest';
import {
  blockContaining,
  blocksOver,
  countRoleOver,
  cycleIndexOn,
  describeAssignment,
  hitchNumberOn,
  hitchRange,
  hitchRangeOn,
  isActiveOn,
  nextRoleChange,
  roleOn,
  rolesOver,
  rotationAssignment,
} from '../../../../src/domain/rotation/assignment.js';
import { parseRotationPattern } from '../../../../src/domain/rotation/pattern.js';
import { formatCivilDate, parseCivilDate } from '../../../../src/domain/time/civil.js';
import { formatDateRange, parseDateRange } from '../../../../src/domain/time/range.js';

const d = parseCivilDate;
const r = parseDateRange;
const fortnights = parseRotationPattern('14/14');
const withTravel = parseRotationPattern('1travel 13on 1travel 13off');

const base = rotationAssignment({ pattern: fortnights, anchor: d('2025-01-06') });

describe('construction', () => {
  it('defaults the phase to zero', () => {
    expect(base.phase).toBe(0);
  });

  it('refuses a phase outside the cycle', () => {
    expect(() =>
      rotationAssignment({ pattern: fortnights, anchor: d('2025-01-06'), phase: 28 }),
    ).toThrow(/between 0 and 27/);
  });

  it('refuses a fractional phase', () => {
    expect(() =>
      rotationAssignment({ pattern: fortnights, anchor: d('2025-01-06'), phase: 1.5 }),
    ).toThrow(/whole number/);
  });
});

describe('roles across the cycle', () => {
  it('starts on shift at the anchor', () => {
    expect(roleOn(base, d('2025-01-06'))).toBe('onshift');
    expect(cycleIndexOn(base, d('2025-01-06'))).toBe(0);
  });

  it('turns over after fourteen days', () => {
    expect(roleOn(base, d('2025-01-19'))).toBe('onshift');
    expect(roleOn(base, d('2025-01-20'))).toBe('offshift');
    expect(roleOn(base, d('2025-02-02'))).toBe('offshift');
    expect(roleOn(base, d('2025-02-03'))).toBe('onshift');
  });

  it('runs backwards from the anchor too', () => {
    expect(roleOn(base, d('2025-01-05'))).toBe('offshift');
    expect(cycleIndexOn(base, d('2025-01-05'))).toBe(27);
  });

  it('places the back to back crew half a cycle away', () => {
    const backToBack = rotationAssignment({
      pattern: fortnights,
      anchor: d('2025-01-06'),
      phase: 14,
    });
    expect(roleOn(backToBack, d('2025-01-06'))).toBe('offshift');
    expect(roleOn(backToBack, d('2025-01-20'))).toBe('onshift');
  });

  it('reads travel days where the pattern has them', () => {
    const flying = rotationAssignment({ pattern: withTravel, anchor: d('2025-01-06') });
    expect(roleOn(flying, d('2025-01-06'))).toBe('travel');
    expect(roleOn(flying, d('2025-01-07'))).toBe('onshift');
    expect(roleOn(flying, d('2025-01-20'))).toBe('travel');
    expect(roleOn(flying, d('2025-01-21'))).toBe('offshift');
  });
});

describe('hitch numbering', () => {
  it('counts the anchor cycle as zero', () => {
    expect(hitchNumberOn(base, d('2025-01-06'))).toBe(0);
    expect(hitchNumberOn(base, d('2025-02-02'))).toBe(0);
    expect(hitchNumberOn(base, d('2025-02-03'))).toBe(1);
  });

  it('goes negative before the anchor', () => {
    expect(hitchNumberOn(base, d('2025-01-05'))).toBe(-1);
  });

  it('gives the dates of a numbered hitch', () => {
    expect(formatDateRange(hitchRange(base, 0))).toBe('2025-01-06..2025-02-02');
    expect(formatDateRange(hitchRange(base, 1))).toBe('2025-02-03..2025-03-02');
    expect(formatDateRange(hitchRange(base, -1))).toBe('2024-12-09..2025-01-05');
  });

  it('finds the hitch a date belongs to', () => {
    expect(formatDateRange(hitchRangeOn(base, d('2025-01-30')))).toBe('2025-01-06..2025-02-02');
  });

  it('shifts the hitch window when the phase moves', () => {
    const shifted = rotationAssignment({ pattern: fortnights, anchor: d('2025-01-06'), phase: 7 });
    expect(formatDateRange(hitchRange(shifted, 0))).toBe('2024-12-30..2025-01-26');
  });
});

describe('blocks', () => {
  it('finds the run of days around a date', () => {
    const block = blockContaining(base, d('2025-01-10'));
    expect(block.role).toBe('onshift');
    expect(formatDateRange(block.range)).toBe('2025-01-06..2025-01-19');
  });

  it('finds a single day travel block', () => {
    const flying = rotationAssignment({ pattern: withTravel, anchor: d('2025-01-06') });
    const block = blockContaining(flying, d('2025-01-06'));
    expect(formatDateRange(block.range)).toBe('2025-01-06..2025-01-06');
  });

  it('reports the next change of role', () => {
    expect(formatCivilDate(nextRoleChange(base, d('2025-01-10')))).toBe('2025-01-20');
  });

  it('splits a range into blocks and clips the ends', () => {
    const blocks = blocksOver(base, r('2025-01-15..2025-02-05'));
    expect(blocks.map((block) => `${block.role} ${formatDateRange(block.range)}`)).toEqual([
      'onshift 2025-01-15..2025-01-19',
      'offshift 2025-01-20..2025-02-02',
      'onshift 2025-02-03..2025-02-05',
    ]);
  });
});

describe('counting over a range', () => {
  it('lists a role for every day', () => {
    expect(rolesOver(base, r('2025-01-18..2025-01-21'))).toEqual([
      'onshift',
      'onshift',
      'offshift',
      'offshift',
    ]);
  });

  it('counts days of one role', () => {
    expect(countRoleOver(base, r('2025-01-06..2025-02-02'), 'onshift')).toBe(14);
    expect(countRoleOver(base, r('2025-01-06..2025-02-02'), 'travel')).toBe(0);
  });
});

describe('effective window', () => {
  it('is always active when no window is set', () => {
    expect(isActiveOn(base, d('2030-01-01'))).toBe(true);
  });

  it('honours the window when one is set', () => {
    const bounded = rotationAssignment({
      pattern: fortnights,
      anchor: d('2025-01-06'),
      effective: r('2025-01-06..2025-06-30'),
    });
    expect(isActiveOn(bounded, d('2025-03-01'))).toBe(true);
    expect(isActiveOn(bounded, d('2025-07-01'))).toBe(false);
  });

  it('describes itself for a log line', () => {
    expect(describeAssignment(base)).toContain('anchored 2025-01-06');
  });
});
