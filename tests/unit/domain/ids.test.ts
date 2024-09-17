import { describe, expect, it } from 'vitest';
import {
  checkInId,
  crewId,
  crewMemberId,
  habitId,
  idPrefix,
  isWellFormed,
  mintId,
} from '../../../src/domain/ids.js';
import { ValidationError } from '../../../src/domain/errors.js';

const GOOD_SUFFIX = '0123456789ABCDEF';

describe('id parsing', () => {
  it('accepts a well formed id for each kind', () => {
    expect(crewMemberId(`mem_${GOOD_SUFFIX}`)).toBe(`mem_${GOOD_SUFFIX}`);
    expect(crewId(`crw_${GOOD_SUFFIX}`)).toBe(`crw_${GOOD_SUFFIX}`);
    expect(habitId(`hab_${GOOD_SUFFIX}`)).toBe(`hab_${GOOD_SUFFIX}`);
    expect(checkInId(`chk_${GOOD_SUFFIX}`)).toBe(`chk_${GOOD_SUFFIX}`);
  });

  it('rejects an id carrying another kind of prefix', () => {
    expect(() => habitId(`crw_${GOOD_SUFFIX}`)).toThrow(ValidationError);
    expect(() => habitId(`crw_${GOOD_SUFFIX}`)).toThrow(/must start with hab_/);
  });

  it('rejects an id with no separator at all', () => {
    expect(() => crewId(`crw${GOOD_SUFFIX}`)).toThrow(/must look like crw_/);
  });

  it('rejects the letters left out of the alphabet', () => {
    expect(() => crewId('crw_ILOU456789ABCDEF')).toThrow(/unreadable suffix/);
  });

  it('rejects a suffix of the wrong length', () => {
    expect(() => crewId('crw_0123')).toThrow(/unreadable suffix/);
    expect(() => crewId(`crw_${GOOD_SUFFIX}0`)).toThrow(/unreadable suffix/);
  });
});

describe('isWellFormed', () => {
  it('answers without throwing', () => {
    expect(isWellFormed('HabitId', `hab_${GOOD_SUFFIX}`)).toBe(true);
    expect(isWellFormed('HabitId', 'hab_lowercase123456')).toBe(false);
    expect(isWellFormed('CrewId', '')).toBe(false);
  });
});

describe('mintId', () => {
  it('builds a parseable id from supplied bytes', () => {
    const bytes = Uint8Array.from({ length: 16 }, (_, index) => index);
    const minted = mintId('HabitId', bytes);
    expect(minted).toBe('hab_0123456789ABCDEF');
    expect(isWellFormed('HabitId', minted)).toBe(true);
  });

  it('wraps byte values larger than the alphabet', () => {
    const bytes = new Uint8Array(16).fill(33);
    expect(mintId('CrewId', bytes)).toBe('crw_1111111111111111');
  });

  it('refuses to mint from too few bytes', () => {
    expect(() => mintId('CrewId', new Uint8Array(4))).toThrow(/needs at least 16 entries/);
  });

  it('exposes the prefix used for a kind', () => {
    expect(idPrefix('CheckInId')).toBe('chk');
  });
});
