import { ValidationError } from '../domain/errors.js';

/**
 * Reading comma separated files back in. Written as a state machine rather
 * than a split on commas, because the files that turn up have quoted notes in
 * them with commas and newlines inside, and a naive split silently loses rows.
 */
export type Cell = string;
export type CsvRow = Cell[];

interface Reader {
  readonly text: string;
  index: number;
}

function readQuoted(reader: Reader): string {
  let value = '';
  reader.index += 1;
  while (reader.index < reader.text.length) {
    const character = reader.text[reader.index] ?? '';
    if (character === '"') {
      if (reader.text[reader.index + 1] === '"') {
        value += '"';
        reader.index += 2;
        continue;
      }
      reader.index += 1;
      return value;
    }
    value += character;
    reader.index += 1;
  }
  throw ValidationError.field('csv', 'ends inside a quoted value');
}

function readPlain(reader: Reader): string {
  let value = '';
  while (reader.index < reader.text.length) {
    const character = reader.text[reader.index] ?? '';
    if (character === ',' || character === '\n' || character === '\r') break;
    if (character === '"') {
      throw ValidationError.field('csv', 'has a quote in the middle of a value');
    }
    value += character;
    reader.index += 1;
  }
  return value;
}

function atLineEnd(reader: Reader): boolean {
  const character = reader.text[reader.index];
  return character === '\n' || character === '\r';
}

function skipLineEnd(reader: Reader): void {
  if (reader.text[reader.index] === '\r') reader.index += 1;
  if (reader.text[reader.index] === '\n') reader.index += 1;
}

export function parseCsv(text: string): CsvRow[] {
  if (text.length === 0) return [];
  const reader: Reader = { text, index: 0 };
  const rows: CsvRow[] = [];
  let row: CsvRow = [];

  for (;;) {
    row.push(text[reader.index] === '"' ? readQuoted(reader) : readPlain(reader));

    if (reader.index >= text.length) {
      rows.push(row);
      break;
    }
    if (text[reader.index] === ',') {
      reader.index += 1;
      if (reader.index >= text.length) {
        row.push('');
        rows.push(row);
        break;
      }
      continue;
    }
    if (atLineEnd(reader)) {
      skipLineEnd(reader);
      rows.push(row);
      row = [];
      if (reader.index >= text.length) break;
      continue;
    }
    throw ValidationError.field('csv', `has an unexpected character at ${String(reader.index)}`);
  }

  return rows;
}

export interface CsvTable {
  readonly headers: readonly string[];
  readonly rows: readonly Record<string, string>[];
}

/** Reads the first line as a header and keys every row by it. */
export function readTable(text: string): CsvTable {
  const rows = parseCsv(text);
  const headers = rows[0];
  if (headers === undefined || headers.length === 0) {
    throw ValidationError.field('csv', 'has no header line');
  }
  const trimmed = headers.map((header) => header.trim().toLowerCase());
  const seen = new Set<string>();
  for (const header of trimmed) {
    if (seen.has(header)) {
      throw ValidationError.field('csv', `names the column ${header} twice`);
    }
    seen.add(header);
  }

  const body: Record<string, string>[] = [];
  for (let index = 1; index < rows.length; index += 1) {
    const cells = rows[index] ?? [];
    if (cells.length === 1 && (cells[0] ?? '').trim().length === 0) continue;
    if (cells.length !== trimmed.length) {
      throw ValidationError.field(
        'csv',
        `line ${String(index + 1)} has ${String(cells.length)} cells, not ${String(trimmed.length)}`,
      );
    }
    const row: Record<string, string> = {};
    trimmed.forEach((header, position) => {
      row[header] = cells[position] ?? '';
    });
    body.push(row);
  }

  return { headers: trimmed, rows: body };
}

export function requireColumns(table: CsvTable, wanted: readonly string[]): void {
  const missing = wanted.filter((header) => !table.headers.includes(header));
  if (missing.length > 0) {
    throw ValidationError.field('csv', `is missing the columns ${missing.join(', ')}`);
  }
}
