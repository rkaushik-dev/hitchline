import { describe, expect, it } from 'vitest';
import {
  countRole,
  cycleLength,
  dutyFraction,
  formatRotationPattern,
  isDayRole,
  parseRotationPattern,
  roleAtIndex,
  rotationPattern,
} from '../../../../src/domain/rotation/pattern.js';

describe('shorthand patterns', () => {
  it('reads a fortnight each way', () => {
    const pattern = parseRotationPattern('14/14');
    expect(cycleLength(pattern)).toBe(28);
    expect(countRole(pattern, 'onshift')).toBe(14);
    expect(countRole(pattern, 'offshift')).toBe(14);
  });

  it('reads an uneven rota', () => {
    const pattern = parseRotationPattern('21/28');
    expect(cycleLength(pattern)).toBe(49);
    expect(dutyFraction(pattern)).toBeCloseTo(21 / 49);
  });

  it('reads the swing pattern by name', () => {
    const pattern = parseRotationPattern('2-2-3');
    expect(cycleLength(pattern)).toBe(14);
    expect(countRole(pattern, 'onshift')).toBe(7);
    expect(pattern.cycle.slice(0, 5)).toEqual([
      'onshift',
      'onshift',
      'offshift',
      'offshift',
      'onshift',
    ]);
  });
});

describe('explicit segments', () => {
  it('reads a run of segments with travel days', () => {
    const pattern = parseRotationPattern('1travel 13on 1travel 13off');
    expect(cycleLength(pattern)).toBe(28);
    expect(countRole(pattern, 'travel')).toBe(2);
    expect(pattern.cycle[0]).toBe('travel');
    expect(pattern.cycle[1]).toBe('onshift');
  });

  it('accepts commas as well as spaces', () => {
    const pattern = parseRotationPattern('7on,7off');
    expect(cycleLength(pattern)).toBe(14);
  });

  it('accepts the long role words', () => {
    const pattern = parseRotationPattern('5onshift 2offshift 3standby');
    expect(countRole(pattern, 'standby')).toBe(3);
  });

  it('is not case sensitive', () => {
    expect(cycleLength(parseRotationPattern('14ON 14OFF'))).toBe(28);
  });

  it('collapses neighbouring segments of the same role', () => {
    const pattern = parseRotationPattern('7on 7on 14off');
    expect(pattern.segments).toEqual([
      { role: 'onshift', days: 14 },
      { role: 'offshift', days: 14 },
    ]);
  });
});

describe('rejections', () => {
  it('refuses an empty pattern', () => {
    expect(() => parseRotationPattern('   ')).toThrow(/is empty/);
  });

  it('refuses an unknown role word', () => {
    expect(() => parseRotationPattern('14on 14ashore')).toThrow(/does not know the day role/);
  });

  it('refuses a segment it cannot parse', () => {
    expect(() => parseRotationPattern('two weeks on')).toThrow(/cannot read the segment/);
  });

  it('refuses a pattern with no on shift days', () => {
    expect(() => parseRotationPattern('14off')).toThrow(/no on shift days/);
  });

  it('refuses a zero length segment', () => {
    expect(() => rotationPattern([{ role: 'onshift', days: 0 }])).toThrow(/segment of 0 days/);
  });

  it('refuses an empty segment list', () => {
    expect(() => rotationPattern([])).toThrow(/at least one segment/);
  });

  it('refuses a cycle longer than a year', () => {
    expect(() => parseRotationPattern('200on 200off')).toThrow(/longer than a year/);
  });
});

describe('formatting', () => {
  it('writes a canonical form', () => {
    expect(formatRotationPattern(parseRotationPattern('14/14'))).toBe('14on 14off');
    expect(formatRotationPattern(parseRotationPattern('1travel 13on 1travel 13off'))).toBe(
      '1travel 13on 1travel 13off',
    );
  });

  it('round trips through the parser', () => {
    const original = parseRotationPattern('2standby 5on 2off');
    expect(parseRotationPattern(formatRotationPattern(original))).toEqual(original);
  });
});

describe('roleAtIndex', () => {
  const pattern = parseRotationPattern('14/14');

  it('reads inside the first cycle', () => {
    expect(roleAtIndex(pattern, 0)).toBe('onshift');
    expect(roleAtIndex(pattern, 13)).toBe('onshift');
    expect(roleAtIndex(pattern, 14)).toBe('offshift');
  });

  it('wraps forwards', () => {
    expect(roleAtIndex(pattern, 28)).toBe('onshift');
    expect(roleAtIndex(pattern, 42)).toBe('offshift');
  });

  it('wraps backwards', () => {
    expect(roleAtIndex(pattern, -1)).toBe('offshift');
    expect(roleAtIndex(pattern, -14)).toBe('offshift');
    expect(roleAtIndex(pattern, -15)).toBe('onshift');
  });
});

describe('isDayRole', () => {
  it('recognises the four roles', () => {
    expect(isDayRole('onshift')).toBe(true);
    expect(isDayRole('standby')).toBe(true);
    expect(isDayRole('holiday')).toBe(false);
  });
});
