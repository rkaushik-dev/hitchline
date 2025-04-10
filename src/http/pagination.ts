import { ValidationError } from '../domain/errors.js';

/**
 * Cursor paging. Offsets are wrong for a list that is still being appended to:
 * a crew member checking in while a supervisor pages through the list would
 * push a row from page one onto page two. A cursor names the last row seen,
 * so a new row at the end never moves an old one.
 */
export interface PageRequest {
  readonly limit: number;
  readonly after: string | undefined;
}

export interface Page<T> {
  readonly items: readonly T[];
  readonly nextCursor: string | undefined;
  readonly total: number;
}

export const DEFAULT_LIMIT = 50;
export const MAX_LIMIT = 500;

export function encodeCursor(key: string): string {
  return Buffer.from(key, 'utf8').toString('base64url');
}

export function decodeCursor(cursor: string): string {
  const decoded = Buffer.from(cursor, 'base64url').toString('utf8');
  if (decoded.length === 0) {
    throw ValidationError.field('after', 'is not a cursor this api handed out');
  }
  return decoded;
}

export function readPageRequest(query: unknown): PageRequest {
  const source = (query ?? {}) as { limit?: string; after?: string };
  const limit = source.limit === undefined ? DEFAULT_LIMIT : Number(source.limit);
  if (!Number.isInteger(limit) || limit < 1 || limit > MAX_LIMIT) {
    throw ValidationError.field('limit', `must be between 1 and ${MAX_LIMIT}`);
  }
  return { limit, after: source.after };
}

/**
 * Cuts a page out of an ordered list. The list has to be in a stable order
 * already, which every repository read here guarantees.
 */
export function paginate<T>(
  items: readonly T[],
  request: PageRequest,
  keyOf: (item: T) => string,
): Page<T> {
  let start = 0;
  if (request.after !== undefined) {
    const wanted = decodeCursor(request.after);
    const at = items.findIndex((item) => keyOf(item) === wanted);
    if (at < 0) {
      throw ValidationError.field('after', 'names a row that is no longer in this list');
    }
    start = at + 1;
  }

  const slice = items.slice(start, start + request.limit);
  const last = slice[slice.length - 1];
  const more = start + slice.length < items.length;
  return {
    items: slice,
    nextCursor: more && last !== undefined ? encodeCursor(keyOf(last)) : undefined,
    total: items.length,
  };
}

/** The body shape every paged endpoint answers with. */
export function pageBody<T, V>(page: Page<T>, view: (item: T) => V): Record<string, unknown> {
  return {
    items: page.items.map(view),
    nextCursor: page.nextCursor ?? null,
    total: page.total,
  };
}
