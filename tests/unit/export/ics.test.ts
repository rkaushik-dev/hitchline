import { describe, expect, it } from 'vitest';
import {
  eventFor,
  foldLine,
  icsDate,
  icsStamp,
  icsText,
  obligationsToCalendar,
  toCalendar,
  type CalendarEvent,
} from '../../../src/export/ics.js';
import { obligation } from '../../../src/domain/schedule/obligation.js';
import { crewMemberId, habitId } from '../../../src/domain/ids.js';
import { parseCivilDate } from '../../../src/domain/time/civil.js';
import { parseDateRange } from '../../../src/domain/time/range.js';
import { parseInstant } from '../../../src/domain/time/zone.js';
import { ValidationError } from '../../../src/domain/errors.js';
import { aHabit } from '../../support/fixtures.js';

const stampedAt = parseInstant('2025-03-17T09:00:00Z');
const ada = crewMemberId('mem_0123456789ABCDEF');
const treadmill = habitId('hab_0123456789ABCDEF');

const daily = obligation({
  habitId: treadmill,
  memberId: ada,
  scope: 'day',
  window: parseDateRange('2025-01-10..2025-01-10'),
  times: 1,
});

describe('escaping', () => {
  it('escapes the reserved characters', () => {
    expect(icsText('a;b,c')).toBe('a\\;b\\,c');
    expect(icsText('a\\b')).toBe('a\\\\b');
    expect(icsText('two\nlines')).toBe('two\\nlines');
  });

  it('leaves plain text alone', () => {
    expect(icsText('Twenty minutes on the treadmill')).toBe('Twenty minutes on the treadmill');
  });
});

describe('dates and stamps', () => {
  it('writes a date with no separators', () => {
    expect(icsDate(parseCivilDate('2025-01-10'))).toBe('20250110');
  });

  it('writes a stamp with no separators or milliseconds', () => {
    expect(icsStamp(stampedAt)).toBe('20250317T090000Z');
  });
});

describe('folding', () => {
  it('leaves a short line alone', () => {
    expect(foldLine('SUMMARY:Row')).toBe('SUMMARY:Row');
  });

  it('folds a long line with a leading space', () => {
    const folded = foldLine(`SUMMARY:${'x'.repeat(200)}`);
    const lines = folded.split('\r\n');
    expect(lines.length).toBeGreaterThan(1);
    expect(lines[0]?.length).toBe(75);
    expect(lines[1]?.startsWith(' ')).toBe(true);
  });

  it('counts octets rather than characters', () => {
    const folded = foldLine(`SUMMARY:${'é'.repeat(60)}`);
    const first = folded.split('\r\n')[0] ?? '';
    expect(new TextEncoder().encode(first).length).toBeLessThanOrEqual(75);
  });
});

describe('building events', () => {
  it('puts the event on the day it falls due', () => {
    const event = eventFor(aHabit(), daily);
    expect(event.from).toEqual(parseCivilDate('2025-01-10'));
    expect(event.to).toEqual(parseCivilDate('2025-01-11'));
  });

  it('names the habit and how many times', () => {
    const event = eventFor(aHabit(), daily);
    expect(event.summary).toBe('Twenty minutes on the treadmill');

    const perHitch = obligation({
      habitId: treadmill,
      memberId: ada,
      scope: 'hitch',
      window: parseDateRange('2025-01-06..2025-02-02'),
      times: 3,
    });
    expect(eventFor(aHabit(), perHitch).summary).toContain('(3 times)');
  });

  it('keys the event on the obligation', () => {
    expect(eventFor(aHabit(), daily).uid).toBe('hab_0123456789ABCDEF:day:2025-01-10');
  });
});

describe('the calendar', () => {
  const events: CalendarEvent[] = [eventFor(aHabit(), daily)];

  it('opens and closes properly', () => {
    const calendar = toCalendar(events, { name: 'Ada', stampedAt });
    const lines = calendar.split('\r\n');
    expect(lines[0]).toBe('BEGIN:VCALENDAR');
    expect(lines.at(-2)).toBe('END:VCALENDAR');
    expect(calendar.endsWith('\r\n')).toBe(true);
  });

  it('writes one event block', () => {
    const calendar = toCalendar(events, { name: 'Ada', stampedAt });
    expect(calendar).toContain('BEGIN:VEVENT');
    expect(calendar).toContain('DTSTART;VALUE=DATE:20250110');
    expect(calendar).toContain('DTEND;VALUE=DATE:20250111');
    expect(calendar).toContain('DTSTAMP:20250317T090000Z');
  });

  it('takes a domain for the uid', () => {
    const calendar = toCalendar(events, { name: 'Ada', stampedAt, domain: 'grane.example' });
    expect(calendar).toContain('@grane.example');
  });

  it('refuses a calendar with no name', () => {
    expect(() => toCalendar(events, { name: '  ', stampedAt })).toThrow(ValidationError);
  });

  it('writes an empty calendar with no events', () => {
    const calendar = toCalendar([], { name: 'Ada', stampedAt });
    expect(calendar).not.toContain('BEGIN:VEVENT');
  });
});

describe('building from obligations', () => {
  it('skips an obligation whose habit is missing', () => {
    const calendar = obligationsToCalendar([], [daily], { name: 'Ada', stampedAt });
    expect(calendar).not.toContain('BEGIN:VEVENT');
  });

  it('writes an event for each obligation it can name', () => {
    const second = obligation({
      habitId: treadmill,
      memberId: ada,
      scope: 'day',
      window: parseDateRange('2025-01-11..2025-01-11'),
      times: 1,
    });
    const calendar = obligationsToCalendar([aHabit()], [daily, second], {
      name: 'Ada',
      stampedAt,
    });
    expect(calendar.split('BEGIN:VEVENT')).toHaveLength(3);
  });
});
