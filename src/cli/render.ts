import { ValidationError } from '../domain/errors.js';

/**
 * Terminal output. Everything the cli prints goes through here so a report on
 * a narrow ssh window lines up the same way it does on a wide one, and so the
 * tests can assert on a string rather than on a stream.
 */
export interface Column<T> {
  readonly header: string;
  readonly get: (row: T) => string;
  readonly align?: 'left' | 'right';
}

const PAD = '  ';

function widthOf(text: string): number {
  return [...text].length;
}

function pad(text: string, width: number, align: 'left' | 'right'): string {
  const gap = Math.max(0, width - widthOf(text));
  return align === 'right' ? `${' '.repeat(gap)}${text}` : `${text}${' '.repeat(gap)}`;
}

export function renderTable<T>(rows: readonly T[], columns: readonly Column<T>[]): string {
  if (columns.length === 0) {
    throw ValidationError.field('columns', 'must name at least one column');
  }
  const cells = rows.map((row) => columns.map((column) => column.get(row)));
  const widths = columns.map((column, index) =>
    cells.reduce(
      (widest, line) => Math.max(widest, widthOf(line[index] ?? '')),
      widthOf(column.header),
    ),
  );

  const header = columns
    .map((column, index) => pad(column.header, widths[index] ?? 0, column.align ?? 'left'))
    .join(PAD)
    .trimEnd();
  const rule = widths
    .map((width) => '-'.repeat(width))
    .join(PAD)
    .trimEnd();
  const body = cells.map((line) =>
    line
      .map((cell, index) => pad(cell, widths[index] ?? 0, columns[index]?.align ?? 'left'))
      .join(PAD)
      .trimEnd(),
  );

  return [header, rule, ...body].join('\n');
}

/** A short bar for a rate between zero and one, ten characters wide. */
export function bar(fraction: number, width = 10): string {
  if (!Number.isFinite(fraction)) return ' '.repeat(width);
  const clamped = Math.min(1, Math.max(0, fraction));
  const filled = Math.round(clamped * width);
  return `${'#'.repeat(filled)}${'.'.repeat(width - filled)}`;
}

export function percent(fraction: number): string {
  if (!Number.isFinite(fraction)) return '   -';
  return `${String(Math.round(fraction * 100)).padStart(3, ' ')}%`;
}

export function heading(text: string): string {
  return `${text}\n${'='.repeat(widthOf(text))}`;
}

export function keyValues(pairs: readonly (readonly [string, string])[]): string {
  const width = pairs.reduce((widest, [key]) => Math.max(widest, widthOf(key)), 0);
  return pairs.map(([key, value]) => `${pad(key, width, 'left')}  ${value}`).join('\n');
}

export function pluralise(count: number, one: string, many = `${one}s`): string {
  return `${String(count)} ${count === 1 ? one : many}`;
}
