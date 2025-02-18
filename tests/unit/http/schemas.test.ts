import { describe, expect, it } from 'vitest';
import {
  amendCheckInSchema,
  createCrewSchema,
  createHabitSchema,
  enrolSchema,
  habitIdSchema,
  holdSchema,
  memberIdSchema,
  parseBody,
  policySchema,
  recordCheckInSchema,
  shieldSchema,
  updateHabitSchema,
  visibilitySchema,
  windowQuerySchema,
} from '../../../src/http/schemas.js';
import { ValidationError } from '../../../src/domain/errors.js';

describe('id shapes', () => {
  it('accepts a well formed id', () => {
    expect(parseBody(memberIdSchema, 'mem_0123456789ABCDEF')).toBe('mem_0123456789ABCDEF');
    expect(parseBody(habitIdSchema, 'hab_0123456789ABCDEF')).toBe('hab_0123456789ABCDEF');
  });

  it('refuses the wrong prefix or a short suffix', () => {
    expect(() => parseBody(memberIdSchema, 'hab_0123456789ABCDEF')).toThrow(/must be a mem id/);
    expect(() => parseBody(memberIdSchema, 'mem_0123')).toThrow(ValidationError);
  });

  it('refuses the letters the alphabet leaves out', () => {
    expect(() => parseBody(memberIdSchema, 'mem_ILOU456789ABCDEF')).toThrow(/must be a mem id/);
  });
});

describe('creating a habit', () => {
  const body = {
    memberId: 'mem_0123456789ABCDEF',
    name: 'Twenty minutes on the treadmill',
    cadence: '1/onshift-day',
  };

  it('accepts the smallest useful body', () => {
    expect(parseBody(createHabitSchema, body)).toEqual(body);
  });

  it('accepts the optional fields', () => {
    const full = { ...body, target: 'at least 20 minutes', startsOn: '2025-01-06', graceDays: 2 };
    expect(parseBody(createHabitSchema, full).graceDays).toBe(2);
  });

  it('refuses a short name', () => {
    expect(() => parseBody(createHabitSchema, { ...body, name: 'go' })).toThrow(ValidationError);
  });

  it('refuses a date that is not iso', () => {
    expect(() => parseBody(createHabitSchema, { ...body, startsOn: '6 January' })).toThrow(
      /YYYY-MM-DD/,
    );
  });

  it('refuses too many tags', () => {
    const tags = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i'];
    expect(() => parseBody(createHabitSchema, { ...body, tags })).toThrow(ValidationError);
  });

  it('names the field that failed', () => {
    try {
      parseBody(createHabitSchema, { ...body, name: 'go' });
      expect.unreachable('should have thrown');
    } catch (error) {
      expect((error as ValidationError).details[0]?.path).toBe('name');
    }
  });
});

describe('updating a habit', () => {
  it('accepts one field', () => {
    expect(parseBody(updateHabitSchema, { status: 'paused' })).toEqual({ status: 'paused' });
  });

  it('accepts a null target, which clears it', () => {
    expect(parseBody(updateHabitSchema, { target: null }).target).toBeNull();
  });

  it('refuses an empty body', () => {
    expect(() => parseBody(updateHabitSchema, {})).toThrow(/must change something/);
  });

  it('refuses a status it does not know', () => {
    expect(() => parseBody(updateHabitSchema, { status: 'retired' })).toThrow(ValidationError);
  });
});

describe('check ins', () => {
  it('accepts a bare habit id', () => {
    const body = { habitId: 'hab_0123456789ABCDEF' };
    expect(parseBody(recordCheckInSchema, body)).toEqual(body);
  });

  it('accepts a timestamp with an offset', () => {
    const body = { habitId: 'hab_0123456789ABCDEF', at: '2025-01-10T09:00:00Z' };
    expect(parseBody(recordCheckInSchema, body).at).toBe('2025-01-10T09:00:00Z');
  });

  it('refuses a timestamp without one', () => {
    const body = { habitId: 'hab_0123456789ABCDEF', at: '2025-01-10 09:00' };
    expect(() => parseBody(recordCheckInSchema, body)).toThrow(ValidationError);
  });

  it('refuses a negative reading', () => {
    const body = { habitId: 'hab_0123456789ABCDEF', reading: -1 };
    expect(() => parseBody(recordCheckInSchema, body)).toThrow(ValidationError);
  });

  it('accepts a null reading on an amendment', () => {
    expect(parseBody(amendCheckInSchema, { reading: null }).reading).toBeNull();
  });
});

describe('roster bodies', () => {
  it('accepts a hold', () => {
    const body = {
      memberId: 'mem_0123456789ABCDEF',
      range: '2025-01-20..2025-01-22',
      role: 'onshift',
      reason: 'held_over',
    };
    expect(parseBody(holdSchema, body).role).toBe('onshift');
  });

  it('refuses a window that is not a pair of dates', () => {
    const body = {
      memberId: 'mem_0123456789ABCDEF',
      range: '2025-01-20',
      role: 'onshift',
      reason: 'held_over',
    };
    expect(() => parseBody(holdSchema, body)).toThrow(/YYYY-MM-DD\.\.YYYY-MM-DD/);
  });

  it('accepts a shield with a granter', () => {
    const body = {
      memberId: 'mem_0123456789ABCDEF',
      range: '2025-02-03..2025-02-07',
      reason: 'training',
      grantedBy: 'mem_FEDCBA9876543210',
    };
    expect(parseBody(shieldSchema, body).grantedBy).toBe('mem_FEDCBA9876543210');
  });

  it('reads a window query', () => {
    expect(parseBody(windowQuerySchema, { window: '2025-01-06..2025-01-19' }).window).toBe(
      '2025-01-06..2025-01-19',
    );
  });
});

describe('policy, visibility and crews', () => {
  it('accepts a partial policy', () => {
    expect(parseBody(policySchema, { maxPerDay: 2 }).maxPerDay).toBe(2);
  });

  it('refuses a policy value out of range', () => {
    expect(() => parseBody(policySchema, { maxPerDay: 99 })).toThrow(ValidationError);
  });

  it('accepts a visibility change for one habit', () => {
    const body = { habitId: 'hab_0123456789ABCDEF', visibility: 'crew' };
    expect(parseBody(visibilitySchema, body).visibility).toBe('crew');
  });

  it('accepts a crew', () => {
    const body = { name: 'Alpha production', site: 'Grane B', siteZone: 'Europe/Oslo' };
    expect(parseBody(createCrewSchema, body)).toEqual(body);
  });

  it('accepts an enrolment', () => {
    const body = { memberId: 'mem_0123456789ABCDEF', from: '2025-01-01', role: 'lead' };
    expect(parseBody(enrolSchema, body).role).toBe('lead');
  });
});
