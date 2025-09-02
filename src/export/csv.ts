import { ValidationError } from '../domain/errors.js';
import type { ResolvedCheckIn } from '../domain/checkin/resolve.js';
import type { ObligationResult } from '../domain/adherence/match.js';
import type { RosterDay } from '../app/roster.service.js';
import { formatCivilDate } from '../domain/time/civil.js';
import { formatDateRange } from '../domain/time/range.js';
import { formatInstant } from '../domain/time/zone.js';

/**
 * Comma separated output for the people who will put it in a spreadsheet no
 * matter what the api offers. Quoting follows rfc 4180: double quotes around
 * anything with a comma, a quote or a newline, and quotes doubled inside.
 */
export interface CsvColumn<T> {
  readonly header: string;
  readonly get: (row: T) => string;
}

const NEEDS_QUOTES = /[",\r\n]/;

export function csvCell(value: string): string {
  if (!NEEDS_QUOTES.test(value)) return value;
  return `"${value.replace(/"/g, '""')}"`;
}

export function csvRow(cells: readonly string[]): string {
  return cells.map(csvCell).join(',');
}

export function toCsv<T>(rows: readonly T[], columns: readonly CsvColumn<T>[]): string {
  if (columns.length === 0) {
    throw ValidationError.field('columns', 'must name at least one column');
  }
  const lines = [csvRow(columns.map((column) => column.header))];
  for (const row of rows) {
    lines.push(csvRow(columns.map((column) => column.get(row))));
  }
  return `${lines.join('\r\n')}\r\n`;
}

export const CHECK_IN_COLUMNS: CsvColumn<ResolvedCheckIn>[] = [
  { header: 'id', get: (row) => row.checkIn.id },
  { header: 'habit_id', get: (row) => row.checkIn.habitId },
  { header: 'member_id', get: (row) => row.checkIn.memberId },
  { header: 'at', get: (row) => formatInstant(row.checkIn.at) },
  { header: 'rotation_day', get: (row) => formatCivilDate(row.day.date) },
  { header: 'role', get: (row) => row.day.role },
  { header: 'zone', get: (row) => row.day.zone },
  {
    header: 'reading',
    get: (row) => (row.checkIn.reading === undefined ? '' : String(row.checkIn.reading)),
  },
  { header: 'source', get: (row) => row.checkIn.source },
  { header: 'note', get: (row) => row.checkIn.note },
];

const RESULT_COLUMNS: CsvColumn<ObligationResult>[] = [
  { header: 'habit_id', get: (row) => row.obligation.habitId },
  { header: 'scope', get: (row) => row.obligation.scope },
  { header: 'window', get: (row) => formatDateRange(row.obligation.window) },
  { header: 'due_on', get: (row) => formatCivilDate(row.obligation.dueOn) },
  { header: 'times', get: (row) => String(row.obligation.times) },
  { header: 'count', get: (row) => String(row.count) },
  {
    header: 'total',
    get: (row) => (row.total === undefined ? '' : String(row.total)),
  },
  { header: 'status', get: (row) => row.status },
];

const ROSTER_COLUMNS: CsvColumn<RosterDay>[] = [
  { header: 'date', get: (row) => formatCivilDate(row.date) },
  { header: 'role', get: (row) => row.role },
  { header: 'source', get: (row) => row.source },
  { header: 'zone', get: (row) => row.zone },
];

export function checkInsToCsv(rows: readonly ResolvedCheckIn[]): string {
  return toCsv(rows, CHECK_IN_COLUMNS);
}

export function resultsToCsv(rows: readonly ObligationResult[]): string {
  return toCsv(rows, RESULT_COLUMNS);
}

export function rosterToCsv(rows: readonly RosterDay[]): string {
  return toCsv(rows, ROSTER_COLUMNS);
}
