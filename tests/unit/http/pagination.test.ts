import { describe, expect, it } from 'vitest';
import {
  DEFAULT_LIMIT,
  MAX_LIMIT,
  decodeCursor,
  encodeCursor,
  pageBody,
  paginate,
  readPageRequest,
} from '../../../src/http/pagination.js';
import { ValidationError } from '../../../src/domain/errors.js';

interface Row {
  readonly id: string;
}

const rows: Row[] = Array.from({ length: 10 }, (_, index) => ({
  id: `row_${String(index).padStart(2, '0')}`,
}));
const keyOf = (row: Row): string => row.id;

describe('cursors', () => {
  it('round trips a key', () => {
    expect(decodeCursor(encodeCursor('row_03'))).toBe('row_03');
  });

  it('is url safe', () => {
    expect(encodeCursor('a/b+c?d')).not.toMatch(/[/+=]/);
  });

  it('refuses an empty cursor', () => {
    expect(() => decodeCursor('')).toThrow(ValidationError);
  });
});

describe('reading the query', () => {
  it('defaults the limit', () => {
    expect(readPageRequest({})).toEqual({ limit: DEFAULT_LIMIT, after: undefined });
    expect(readPageRequest(undefined).limit).toBe(DEFAULT_LIMIT);
  });

  it('takes a limit and a cursor', () => {
    expect(readPageRequest({ limit: '10', after: 'abc' })).toEqual({ limit: 10, after: 'abc' });
  });

  it('refuses a limit out of range', () => {
    expect(() => readPageRequest({ limit: '0' })).toThrow(/between 1 and 500/);
    expect(() => readPageRequest({ limit: String(MAX_LIMIT + 1) })).toThrow(/between 1 and 500/);
    expect(() => readPageRequest({ limit: 'lots' })).toThrow(ValidationError);
  });
});

describe('paging', () => {
  it('takes the first page and offers a cursor', () => {
    const page = paginate(rows, { limit: 3, after: undefined }, keyOf);
    expect(page.items.map(keyOf)).toEqual(['row_00', 'row_01', 'row_02']);
    expect(page.total).toBe(10);
    expect(page.nextCursor).toBe(encodeCursor('row_02'));
  });

  it('carries on from a cursor', () => {
    const page = paginate(rows, { limit: 3, after: encodeCursor('row_02') }, keyOf);
    expect(page.items.map(keyOf)).toEqual(['row_03', 'row_04', 'row_05']);
  });

  it('offers no cursor on the last page', () => {
    const page = paginate(rows, { limit: 4, after: encodeCursor('row_05') }, keyOf);
    expect(page.items.map(keyOf)).toEqual(['row_06', 'row_07', 'row_08', 'row_09']);
    expect(page.nextCursor).toBeUndefined();
  });

  it('handles a limit larger than the list', () => {
    const page = paginate(rows, { limit: 100, after: undefined }, keyOf);
    expect(page.items).toHaveLength(10);
    expect(page.nextCursor).toBeUndefined();
  });

  it('pages an empty list', () => {
    const page = paginate([], { limit: 10, after: undefined }, keyOf);
    expect(page.items).toEqual([]);
    expect(page.total).toBe(0);
  });

  it('refuses a cursor naming a row that has gone', () => {
    expect(() => paginate(rows, { limit: 3, after: encodeCursor('row_99') }, keyOf)).toThrow(
      /no longer in this list/,
    );
  });

  it('does not move a row when one is appended', () => {
    const first = paginate(rows, { limit: 3, after: undefined }, keyOf);
    const grown = [...rows, { id: 'row_10' }];
    const second = paginate(grown, { limit: 3, after: first.nextCursor }, keyOf);
    expect(second.items.map(keyOf)).toEqual(['row_03', 'row_04', 'row_05']);
  });
});

describe('the body shape', () => {
  it('writes items, a cursor and a total', () => {
    const page = paginate(rows, { limit: 2, after: undefined }, keyOf);
    expect(pageBody(page, (row) => row.id)).toEqual({
      items: ['row_00', 'row_01'],
      nextCursor: encodeCursor('row_01'),
      total: 10,
    });
  });

  it('writes a null cursor at the end', () => {
    const page = paginate(rows, { limit: 20, after: undefined }, keyOf);
    expect(pageBody(page, (row) => row.id)['nextCursor']).toBeNull();
  });
});
