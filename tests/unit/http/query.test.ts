import { describe, expect, it } from 'vitest';
import type { FastifyRequest } from 'fastify';
import {
  crewOf,
  dateOf,
  habitOf,
  memberOf,
  optionalWindowOf,
  queryMemberOf,
  rawDateOf,
  windowOf,
  windowStringOf,
} from '../../../src/http/query.js';
import { formatCivilDate } from '../../../src/domain/time/civil.js';
import { formatDateRange } from '../../../src/domain/time/range.js';
import { ValidationError } from '../../../src/domain/errors.js';

function request(query: Record<string, unknown>, params: Record<string, string> = {}) {
  return { query, params } as unknown as FastifyRequest;
}

describe('windows', () => {
  it('parses one from the query', () => {
    const range = windowOf(request({ window: '2025-07-01..2025-07-14' }));
    expect(formatDateRange(range)).toBe('2025-07-01..2025-07-14');
  });

  it('says what it was needed for', () => {
    expect(() => windowOf(request({}), 'read a roster')).toThrow(/is needed to read a roster/);
  });

  it('hands back the raw string when a service wants one', () => {
    expect(windowStringOf(request({ window: '2025-07-01..2025-07-14' }))).toBe(
      '2025-07-01..2025-07-14',
    );
  });

  it('is happy with no window when it is optional', () => {
    expect(optionalWindowOf(request({}))).toBeUndefined();
    expect(optionalWindowOf(request({ window: '2025-07-01..2025-07-14' }))).toBeDefined();
  });

  it('still refuses a window it cannot read', () => {
    expect(() => windowOf(request({ window: 'july' }))).toThrow(ValidationError);
  });

  it('ignores a repeated parameter rather than guessing', () => {
    expect(() => windowOf(request({ window: ['a', 'b'] }))).toThrow(/is needed/);
  });
});

describe('dates', () => {
  it('parses one from the query', () => {
    expect(formatCivilDate(dateOf(request({ on: '2025-07-01' })))).toBe('2025-07-01');
  });

  it('says what it was needed for', () => {
    expect(() => dateOf(request({}), 'on', 'find a hitch')).toThrow(/is needed to find a hitch/);
  });

  it('hands back the raw string too', () => {
    expect(rawDateOf(request({ on: '2025-07-01' }))).toBe('2025-07-01');
  });
});

describe('ids', () => {
  it('reads them out of the path', () => {
    const req = request(
      {},
      {
        memberId: 'mem_0123456789ABCDEF',
        habitId: 'hab_0123456789ABCDEF',
        crewId: 'crw_0123456789ABCDEF',
      },
    );
    expect(memberOf(req)).toBe('mem_0123456789ABCDEF');
    expect(habitOf(req)).toBe('hab_0123456789ABCDEF');
    expect(crewOf(req)).toBe('crw_0123456789ABCDEF');
  });

  it('refuses one that is not an id', () => {
    expect(() => memberOf(request({}, { memberId: 'nobody' }))).toThrow(ValidationError);
  });

  it('reads a member out of the query when that is where it is', () => {
    expect(queryMemberOf(request({ memberId: 'mem_0123456789ABCDEF' }))).toBe(
      'mem_0123456789ABCDEF',
    );
    expect(() => queryMemberOf(request({}), 'list habits')).toThrow(/is needed to list habits/);
  });
});
