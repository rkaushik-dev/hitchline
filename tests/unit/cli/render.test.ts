import { describe, expect, it } from 'vitest';
import {
  bar,
  heading,
  keyValues,
  percent,
  pluralise,
  renderTable,
  type Column,
} from '../../../src/cli/render.js';
import { ValidationError } from '../../../src/domain/errors.js';

interface Row {
  readonly date: string;
  readonly role: string;
  readonly count: number;
}

const rows: Row[] = [
  { date: '2025-01-06', role: 'onshift', count: 1 },
  { date: '2025-01-20', role: 'offshift', count: 12 },
];

const columns: Column<Row>[] = [
  { header: 'date', get: (row) => row.date },
  { header: 'role', get: (row) => row.role },
  { header: 'n', get: (row) => String(row.count), align: 'right' },
];

describe('tables', () => {
  it('lines the columns up under the headers', () => {
    const lines = renderTable(rows, columns).split('\n');
    expect(lines[0]).toBe('date        role       n');
    expect(lines[1]).toBe('----------  --------  --');
    expect(lines[2]).toBe('2025-01-06  onshift    1');
    expect(lines[3]).toBe('2025-01-20  offshift  12');
  });

  it('widens a column to fit its header', () => {
    const wide: Column<Row>[] = [{ header: 'the day it fell on', get: (row) => row.date }];
    const lines = renderTable(rows, wide).split('\n');
    expect(lines[0]).toBe('the day it fell on');
    expect(lines[2]).toBe('2025-01-06');
  });

  it('renders a header and a rule with no rows', () => {
    expect(renderTable([], columns).split('\n')).toHaveLength(2);
  });

  it('refuses a table with no columns', () => {
    expect(() => renderTable(rows, [])).toThrow(ValidationError);
  });

  it('does not leave trailing spaces on a line', () => {
    for (const line of renderTable(rows, columns).split('\n')) {
      expect(line).toBe(line.trimEnd());
    }
  });
});

describe('bars and percentages', () => {
  it('fills in proportion', () => {
    expect(bar(0)).toBe('..........');
    expect(bar(0.5)).toBe('#####.....');
    expect(bar(1)).toBe('##########');
  });

  it('clamps out of range values', () => {
    expect(bar(-1)).toBe('..........');
    expect(bar(2)).toBe('##########');
  });

  it('takes a width', () => {
    expect(bar(0.5, 4)).toBe('##..');
  });

  it('renders a blank bar for a value that is not a number', () => {
    expect(bar(Number.NaN)).toBe('          ');
  });

  it('rounds a percentage and keeps the column width', () => {
    expect(percent(0.5)).toBe(' 50%');
    expect(percent(1)).toBe('100%');
    expect(percent(0.004)).toBe('  0%');
    expect(percent(Number.NaN)).toBe('   -');
  });
});

describe('headings and pairs', () => {
  it('underlines a heading to its own width', () => {
    expect(heading('Hitch')).toBe('Hitch\n=====');
  });

  it('lines up keys and values', () => {
    expect(
      keyValues([
        ['window', '2025-01-06..2025-01-19'],
        ['met', '12'],
      ]),
    ).toBe('window  2025-01-06..2025-01-19\nmet     12');
  });

  it('pluralises', () => {
    expect(pluralise(1, 'check in')).toBe('1 check in');
    expect(pluralise(2, 'check in')).toBe('2 check ins');
    expect(pluralise(0, 'hitch', 'hitches')).toBe('0 hitches');
  });
});
