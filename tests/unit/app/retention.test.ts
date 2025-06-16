import { describe, expect, it } from 'vitest';
import {
  DEFAULT_RETENTION,
  describePlan,
  planRetention,
  redact,
  retentionPolicy,
} from '../../../src/app/retention.js';
import { checkIn, type CheckIn } from '../../../src/domain/checkin/checkin.js';
import { checkInId, crewMemberId, habitId } from '../../../src/domain/ids.js';
import { formatCivilDate, parseCivilDate } from '../../../src/domain/time/civil.js';
import { parseInstant } from '../../../src/domain/time/zone.js';
import { ValidationError } from '../../../src/domain/errors.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const treadmill = habitId('hab_0123456789ABCDEF');
const today = parseCivilDate('2025-06-16');
let next = 0;

function at(iso: string, note = ''): CheckIn {
  next += 1;
  return checkIn({
    id: checkInId(`chk_${next.toString().padStart(16, '0')}`),
    habitId: treadmill,
    memberId: ada,
    at: parseInstant(iso),
    recordedAt: parseInstant(iso),
    source: 'app',
    note,
  });
}

describe('the policy', () => {
  it('has a default that keeps five years', () => {
    expect(DEFAULT_RETENTION.keepMonths).toBe(60);
    expect(retentionPolicy()).toEqual(DEFAULT_RETENTION);
  });

  it('takes an override for one field', () => {
    expect(retentionPolicy({ keepMonths: 24 }).keepMonths).toBe(24);
  });

  it('refuses a period that is not a whole number of months', () => {
    expect(() => retentionPolicy({ keepMonths: 0 })).toThrow(ValidationError);
    expect(() => retentionPolicy({ keepMonths: 1.5 })).toThrow(/whole number of months/);
  });

  it('refuses to clear notes later than the rows go', () => {
    expect(() => retentionPolicy({ keepMonths: 12, keepNoteMonths: 24 })).toThrow(
      /cannot be longer than the row is kept/,
    );
  });

  it('refuses a floor above the note window', () => {
    expect(() => retentionPolicy({ keepNoteMonths: 12, floorMonths: 24 })).toThrow(
      /cannot be longer than notes are kept/,
    );
  });
});

describe('planning a prune', () => {
  const policy = retentionPolicy({ keepMonths: 24, keepNoteMonths: 12, floorMonths: 6 });

  it('names the cutoffs it used', () => {
    const plan = planRetention([], today, 'UTC', policy);
    expect(formatCivilDate(plan.cutoff)).toBe('2023-06-16');
    expect(formatCivilDate(plan.noteCutoff)).toBe('2024-06-16');
  });

  it('keeps anything inside the floor whatever else it holds', () => {
    const plan = planRetention([at('2025-05-01T09:00:00Z', 'a note')], today, 'UTC', policy);
    expect(plan.kept).toBe(1);
    expect(plan.toRedact).toEqual([]);
  });

  it('clears the note on a row past the note window', () => {
    const plan = planRetention([at('2024-01-05T09:00:00Z', 'a note')], today, 'UTC', policy);
    expect(plan.toRedact).toHaveLength(1);
    expect(plan.expired).toEqual([]);
  });

  it('leaves a row with no note alone', () => {
    const plan = planRetention([at('2024-01-05T09:00:00Z')], today, 'UTC', policy);
    expect(plan.toRedact).toEqual([]);
    expect(plan.kept).toBe(1);
  });

  it('removes a row past the keep window', () => {
    const plan = planRetention([at('2022-01-05T09:00:00Z', 'a note')], today, 'UTC', policy);
    expect(plan.expired).toHaveLength(1);
    expect(plan.toRedact).toEqual([]);
  });

  it('reads the date in the zone it was given', () => {
    const entry = at('2023-06-15T23:30:00Z');
    expect(planRetention([entry], today, 'UTC', policy).expired).toHaveLength(1);
    expect(planRetention([entry], today, 'Europe/Oslo', policy).expired).toEqual([]);
  });

  it('sorts a mixed pile into the three buckets', () => {
    const plan = planRetention(
      [
        at('2025-05-01T09:00:00Z', 'recent'),
        at('2024-01-05T09:00:00Z', 'middling'),
        at('2022-01-05T09:00:00Z', 'ancient'),
      ],
      today,
      'UTC',
      policy,
    );
    expect(describePlan(plan)).toBe('1 to remove, 1 notes to clear, 1 kept');
  });
});

describe('redacting', () => {
  it('clears the note and leaves everything else', () => {
    const entry = at('2024-01-05T09:00:00Z', 'something personal');
    const cleared = redact(entry);
    expect(cleared.note).toBe('');
    expect(cleared.id).toBe(entry.id);
    expect(cleared.reading).toBe(entry.reading);
  });
});
