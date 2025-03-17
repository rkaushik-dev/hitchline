import { describe, expect, it } from 'vitest';
import {
  CHECK_IN_COLUMNS,
  checkInsToCsv,
  csvCell,
  csvRow,
  resultsToCsv,
  rosterToCsv,
  toCsv,
} from '../../../src/export/csv.js';
import { checkIn } from '../../../src/domain/checkin/checkin.js';
import { resolveAll } from '../../../src/domain/checkin/resolve.js';
import { obligation } from '../../../src/domain/schedule/obligation.js';
import { checkInId, crewMemberId, habitId } from '../../../src/domain/ids.js';
import { parseCivilDate } from '../../../src/domain/time/civil.js';
import { parseDateRange } from '../../../src/domain/time/range.js';
import { parseInstant } from '../../../src/domain/time/zone.js';
import { ValidationError } from '../../../src/domain/errors.js';
import { aMember } from '../../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const treadmill = habitId('hab_0123456789ABCDEF');

describe('quoting', () => {
  it('leaves a plain value alone', () => {
    expect(csvCell('onshift')).toBe('onshift');
  });

  it('quotes a value with a comma', () => {
    expect(csvCell('Vasquez, Ada')).toBe('"Vasquez, Ada"');
  });

  it('doubles a quote inside a quoted value', () => {
    expect(csvCell('said "no"')).toBe('"said ""no"""');
  });

  it('quotes a value with a newline', () => {
    expect(csvCell('two\nlines')).toBe('"two\nlines"');
  });

  it('joins cells with commas', () => {
    expect(csvRow(['a', 'b,c', 'd'])).toBe('a,"b,c",d');
  });
});

describe('building a table', () => {
  const rows = [{ name: 'Ada' }, { name: 'Bo' }];
  const columns = [{ header: 'name', get: (row: { name: string }) => row.name }];

  it('writes a header and one line per row, ending each with crlf', () => {
    expect(toCsv(rows, columns)).toBe('name\r\nAda\r\nBo\r\n');
  });

  it('writes just the header for no rows', () => {
    expect(toCsv([], columns)).toBe('name\r\n');
  });

  it('refuses a table with no columns', () => {
    expect(() => toCsv(rows, [])).toThrow(ValidationError);
  });
});

describe('check in rows', () => {
  const entry = checkIn({
    id: checkInId('chk_0123456789ABCDEF'),
    habitId: treadmill,
    memberId: ada,
    at: parseInstant('2025-01-10T07:30:00Z'),
    recordedAt: parseInstant('2025-01-10T07:31:00Z'),
    source: 'watch',
    reading: 22,
    note: 'before the shift, early',
  });

  it('names every column', () => {
    expect(CHECK_IN_COLUMNS.map((column) => column.header)).toEqual([
      'id',
      'habit_id',
      'member_id',
      'at',
      'rotation_day',
      'role',
      'zone',
      'reading',
      'source',
      'note',
    ]);
  });

  it('writes the rotation day beside the instant', () => {
    const csv = checkInsToCsv(resolveAll(aMember(), [entry]));
    const line = csv.split('\r\n')[1] ?? '';
    expect(line).toContain('2025-01-10T07:30:00.000Z');
    expect(line).toContain('2025-01-10');
    expect(line).toContain('onshift');
    expect(line).toContain('Europe/Oslo');
  });

  it('quotes a note with a comma in it', () => {
    const csv = checkInsToCsv(resolveAll(aMember(), [entry]));
    expect(csv).toContain('"before the shift, early"');
  });

  it('leaves an absent reading empty rather than writing undefined', () => {
    const csv = checkInsToCsv(resolveAll(aMember(), [{ ...entry, reading: undefined }]));
    expect(csv).not.toContain('undefined');
    expect(csv.split('\r\n')[1]).toContain(',,watch,');
  });
});

describe('result rows', () => {
  it('writes the window and the counts', () => {
    const csv = resultsToCsv([
      {
        obligation: obligation({
          habitId: treadmill,
          memberId: ada,
          scope: 'hitch',
          window: parseDateRange('2025-01-06..2025-02-02'),
          times: 3,
        }),
        count: 2,
        total: 40,
        status: 'partial',
        progress: 0.67,
      },
    ]);
    const line = csv.split('\r\n')[1] ?? '';
    expect(line).toBe(
      'hab_0123456789ABCDEF,hitch,2025-01-06..2025-02-02,2025-02-02,3,2,40,partial',
    );
  });
});

describe('roster rows', () => {
  it('writes a line for each day', () => {
    const csv = rosterToCsv([
      {
        date: parseCivilDate('2025-01-06'),
        role: 'onshift',
        source: 'pattern',
        zone: 'Europe/Oslo',
      },
    ]);
    expect(csv).toBe('date,role,source,zone\r\n2025-01-06,onshift,pattern,Europe/Oslo\r\n');
  });
});
