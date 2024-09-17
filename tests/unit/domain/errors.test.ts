import { describe, expect, it } from 'vitest';
import {
  ConflictError,
  DomainError,
  ForbiddenError,
  NotFoundError,
  UnsupportedError,
  ValidationError,
  combineValidation,
  isDomainError,
} from '../../../src/domain/errors.js';

describe('DomainError', () => {
  it('carries a code and defaults to no details', () => {
    const error = new DomainError('conflict', 'two crews claim the same berth');
    expect(error.code).toBe('conflict');
    expect(error.details).toEqual([]);
    expect(error.name).toBe('DomainError');
  });

  it('keeps the subclass name so logs stay readable', () => {
    expect(new ValidationError('bad').name).toBe('ValidationError');
    expect(new ConflictError('bad').name).toBe('ConflictError');
    expect(new ForbiddenError('bad').name).toBe('ForbiddenError');
    expect(new UnsupportedError('bad').name).toBe('UnsupportedError');
  });

  it('serialises without the stack', () => {
    const error = ValidationError.field('cadence.count', 'must be positive');
    expect(error.toJSON()).toEqual({
      code: 'validation_failed',
      message: 'cadence.count must be positive',
      details: [{ path: 'cadence.count', message: 'must be positive' }],
    });
    expect(Object.keys(error.toJSON())).not.toContain('stack');
  });

  it('passes a cause through when one is given', () => {
    const cause = new Error('disk went away');
    const error = new DomainError('conflict', 'write failed', { cause });
    expect(error.cause).toBe(cause);
  });
});

describe('NotFoundError', () => {
  it('names the resource and the id', () => {
    const error = new NotFoundError('habit', 'hab_0123456789ABCDEF');
    expect(error.message).toBe('habit hab_0123456789ABCDEF does not exist');
    expect(error.resource).toBe('habit');
    expect(error.id).toBe('hab_0123456789ABCDEF');
    expect(error.code).toBe('not_found');
  });
});

describe('isDomainError', () => {
  it('accepts every domain failure and rejects anything else', () => {
    expect(isDomainError(new NotFoundError('crew', 'crw_0123456789ABCDEF'))).toBe(true);
    expect(isDomainError(new Error('plain'))).toBe(false);
    expect(isDomainError('validation_failed')).toBe(false);
    expect(isDomainError(null)).toBe(false);
  });
});

describe('combineValidation', () => {
  it('returns undefined when nothing failed', () => {
    expect(combineValidation('habit is invalid', [])).toBeUndefined();
  });

  it('merges details from every failure', () => {
    const combined = combineValidation('habit is invalid', [
      ValidationError.field('name', 'is required'),
      ValidationError.field('cadence', 'is not recognised'),
    ]);
    expect(combined?.details).toEqual([
      { path: 'name', message: 'is required' },
      { path: 'cadence', message: 'is not recognised' },
    ]);
    expect(combined?.message).toBe('habit is invalid');
  });

  it('keeps a detail free failure by pinning it to the empty path', () => {
    const combined = combineValidation('habit is invalid', [new ValidationError('nothing fits')]);
    expect(combined?.details).toEqual([{ path: '', message: 'nothing fits' }]);
  });
});
