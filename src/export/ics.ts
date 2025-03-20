import { ValidationError } from '../domain/errors.js';
import type { Habit } from '../domain/habit/habit.js';
import type { Obligation } from '../domain/schedule/obligation.js';
import { obligationKey } from '../domain/schedule/obligation.js';
import { addDays, formatCivilDate, type CivilDate } from '../domain/time/civil.js';
import type { Instant } from '../domain/time/zone.js';

/**
 * Calendar export. A crew member who lives in their phone calendar wants the
 * hitch and what it asks for to show up there, so each obligation becomes an
 * all day event on the day it falls due.
 */
export interface CalendarOptions {
  readonly name: string;
  readonly stampedAt: Instant;
  readonly domain?: string;
}

const CRLF = '\r\n';
const MAX_OCTETS = 75;

/** Escapes the four characters the format reserves inside a text value. */
export function icsText(value: string): string {
  return value
    .replace(/\\/g, '\\\\')
    .replace(/;/g, '\\;')
    .replace(/,/g, '\\,')
    .replace(/\r?\n/g, '\\n');
}

export function icsDate(date: CivilDate): string {
  return formatCivilDate(date).replace(/-/g, '');
}

export function icsStamp(at: Instant): string {
  return new Date(at.epochMs)
    .toISOString()
    .replace(/[-:]/g, '')
    .replace(/\.\d{3}/, '');
}

/**
 * Folds a line to 75 octets, continuing with a leading space. Counting is in
 * octets rather than characters because a name with an accent in it is two
 * bytes and calendars that count wrong drop the last character.
 */
export function foldLine(line: string): string {
  const encoder = new TextEncoder();
  if (encoder.encode(line).length <= MAX_OCTETS) return line;

  const parts: string[] = [];
  let current = '';
  let width = 0;
  for (const character of line) {
    const size = encoder.encode(character).length;
    const limit = parts.length === 0 ? MAX_OCTETS : MAX_OCTETS - 1;
    if (width + size > limit) {
      parts.push(current);
      current = '';
      width = 0;
    }
    current += character;
    width += size;
  }
  parts.push(current);
  return parts.join(`${CRLF} `);
}

export interface CalendarEvent {
  readonly uid: string;
  readonly summary: string;
  readonly description: string;
  readonly from: CivilDate;
  /** Exclusive, which is how an all day event ends in the format. */
  readonly to: CivilDate;
}

export function eventFor(habit: Habit, obligation: Obligation): CalendarEvent {
  const times = obligation.times === 1 ? '' : ` (${String(obligation.times)} times)`;
  return {
    uid: obligationKey(obligation),
    summary: `${habit.name}${times}`,
    description: `owed per ${obligation.scope}, window ${formatCivilDate(obligation.window.from)} to ${formatCivilDate(obligation.window.to)}`,
    from: obligation.window.to,
    to: addDays(obligation.window.to, 1),
  };
}

export function toCalendar(events: readonly CalendarEvent[], options: CalendarOptions): string {
  if (options.name.trim().length === 0) {
    throw ValidationError.field('name', 'must not be empty');
  }
  const domain = options.domain ?? 'hitchline.local';
  const stamp = icsStamp(options.stampedAt);
  const lines: string[] = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//hitchline//rotation adherence//EN',
    'CALSCALE:GREGORIAN',
    `X-WR-CALNAME:${icsText(options.name)}`,
  ];

  for (const event of events) {
    lines.push(
      'BEGIN:VEVENT',
      `UID:${icsText(event.uid)}@${domain}`,
      `DTSTAMP:${stamp}`,
      `DTSTART;VALUE=DATE:${icsDate(event.from)}`,
      `DTEND;VALUE=DATE:${icsDate(event.to)}`,
      `SUMMARY:${icsText(event.summary)}`,
      `DESCRIPTION:${icsText(event.description)}`,
      'TRANSP:TRANSPARENT',
      'END:VEVENT',
    );
  }

  lines.push('END:VCALENDAR');
  return `${lines.map(foldLine).join(CRLF)}${CRLF}`;
}

export function obligationsToCalendar(
  habits: readonly Habit[],
  obligations: readonly Obligation[],
  options: CalendarOptions,
): string {
  const byId = new Map(habits.map((habit) => [habit.id, habit]));
  const events: CalendarEvent[] = [];
  for (const obligation of obligations) {
    const habit = byId.get(obligation.habitId);
    if (habit === undefined) continue;
    events.push(eventFor(habit, obligation));
  }
  return toCalendar(events, options);
}
