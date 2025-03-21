import { describe, expect, it } from 'vitest';
import { parseCsv, readTable, requireColumns } from '../../../src/import/csv.js';
import { toCsv } from '../../../src/export/csv.js';
import { ValidationError } from '../../../src/domain/errors.js';

describe('parsing rows', () => {
  it('reads plain cells', () => {
    expect(parseCsv('a,b,c\n1,2,3\n')).toEqual([
      ['a', 'b', 'c'],
      ['1', '2', '3'],
    ]);
  });

  it('reads a file with no trailing newline', () => {
    expect(parseCsv('a,b\n1,2')).toEqual([
      ['a', 'b'],
      ['1', '2'],
    ]);
  });

  it('reads carriage returns as line ends', () => {
    expect(parseCsv('a,b\r\n1,2\r\n')).toEqual([
      ['a', 'b'],
      ['1', '2'],
    ]);
  });

  it('reads a quoted cell with a comma in it', () => {
    expect(parseCsv('a,b\n"one, two",3\n')[1]).toEqual(['one, two', '3']);
  });

  it('reads a quoted cell with a newline in it', () => {
    expect(parseCsv('a\n"two\nlines"\n')[1]).toEqual(['two\nlines']);
  });

  it('reads a doubled quote as one quote', () => {
    expect(parseCsv('a\n"said ""no"""\n')[1]).toEqual(['said "no"']);
  });

  it('reads empty cells', () => {
    expect(parseCsv('a,b,c\n1,,3\n')[1]).toEqual(['1', '', '3']);
    expect(parseCsv('a,b\n,\n')[1]).toEqual(['', '']);
  });

  it('reads a trailing empty cell', () => {
    expect(parseCsv('a,b\n1,\n')[1]).toEqual(['1', '']);
  });

  it('refuses a file that ends inside a quote', () => {
    expect(() => parseCsv('a\n"never closed\n')).toThrow(/ends inside a quoted value/);
  });

  it('refuses a quote in the middle of a plain value', () => {
    expect(() => parseCsv('a\nno"pe\n')).toThrow(/quote in the middle/);
  });

  it('round trips what the exporter writes', () => {
    const written = toCsv(
      [{ note: 'one, two' }, { note: 'said "no"' }],
      [{ header: 'note', get: (row: { note: string }) => row.note }],
    );
    expect(parseCsv(written)).toEqual([['note'], ['one, two'], ['said "no"']]);
  });
});

describe('reading a table', () => {
  const text = 'Habit,At,Reading\nhab_1,2025-01-10T07:00:00Z,20\nhab_2,2025-01-11T07:00:00Z,\n';

  it('lowercases the headers', () => {
    expect(readTable(text).headers).toEqual(['habit', 'at', 'reading']);
  });

  it('keys each row by header', () => {
    expect(readTable(text).rows[0]).toEqual({
      habit: 'hab_1',
      at: '2025-01-10T07:00:00Z',
      reading: '20',
    });
  });

  it('keeps an empty cell as an empty string', () => {
    expect(readTable(text).rows[1]?.['reading']).toBe('');
  });

  it('skips a blank line at the end', () => {
    expect(readTable(`${text}\n`).rows).toHaveLength(2);
  });

  it('refuses a file with no header', () => {
    expect(() => readTable('')).toThrow(/has no header line/);
  });

  it('refuses a repeated header', () => {
    expect(() => readTable('a,A\n1,2\n')).toThrow(/names the column a twice/);
  });

  it('refuses a row with the wrong number of cells', () => {
    expect(() => readTable('a,b\n1,2,3\n')).toThrow(/line 2 has 3 cells, not 2/);
  });
});

describe('required columns', () => {
  it('passes when they are all there', () => {
    const table = readTable('habit,at\nhab_1,2025-01-10T07:00:00Z\n');
    expect(() => requireColumns(table, ['habit', 'at'])).not.toThrow();
  });

  it('names the ones that are missing', () => {
    const table = readTable('habit\nhab_1\n');
    expect(() => requireColumns(table, ['habit', 'at', 'reading'])).toThrow(
      /missing the columns at, reading/,
    );
  });

  it('throws a domain failure', () => {
    const table = readTable('habit\nhab_1\n');
    expect(() => requireColumns(table, ['at'])).toThrow(ValidationError);
  });
});
