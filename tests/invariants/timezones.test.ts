import { describe, expect, it } from 'vitest';
import {
  civilDateIn,
  dayLengthMinutes,
  hasTransition,
  parseInstant,
  resolveZoned,
  zonedInstant,
} from '../../src/domain/time/zone.js';
import { parseCivilDate, formatCivilDate } from '../../src/domain/time/civil.js';
import { parseClockTime } from '../../src/domain/time/clocktime.js';
import { rotationDayAt } from '../../src/domain/rotation/zoning.js';
import { resolveAll } from '../../src/domain/checkin/resolve.js';
import { expandHabit } from '../../src/domain/schedule/expand.js';
import { evaluateAll } from '../../src/domain/adherence/match.js';
import { roster } from '../../src/domain/rotation/roster.js';
import { parseDateRange } from '../../src/domain/time/range.js';
import { aCheckIn, resetCheckInIds } from '../support/builders.js';
import { aHabit, aMember } from '../support/fixtures.js';

const d = parseCivilDate;
const t = parseClockTime;

/**
 * The zones that break naive date handling, checked all the way through rather
 * than only at the bottom. Every bug this file guards against was found by
 * somebody offshore, not by a unit test: the clocks move while a crew is on a
 * hitch and the readings have to stay honest across it.
 */
const TRICKY = [
  { zone: 'Europe/London', spring: '2025-03-30', autumn: '2025-10-26' },
  { zone: 'Europe/Oslo', spring: '2025-03-30', autumn: '2025-10-26' },
  { zone: 'America/Halifax', spring: '2025-03-09', autumn: '2025-11-02' },
  { zone: 'America/Santiago', spring: '2025-09-07', autumn: '2025-04-05' },
  { zone: 'Australia/Lord_Howe', spring: '2025-10-05', autumn: '2025-04-06' },
];

describe('the days the clocks move', () => {
  it('are shorter in spring and longer in autumn', () => {
    for (const entry of TRICKY) {
      const springLength = dayLengthMinutes(entry.zone, d(entry.spring));
      const autumnLength = dayLengthMinutes(entry.zone, d(entry.autumn));
      expect(springLength).toBeLessThan(1440);
      expect(autumnLength).toBeGreaterThan(1440);
      expect(hasTransition(entry.zone, d(entry.spring))).toBe(true);
    }
  });

  it('still resolve every wall clock reading to some instant', () => {
    for (const entry of TRICKY) {
      for (let hour = 0; hour < 24; hour += 1) {
        const time = t(`${String(hour).padStart(2, '0')}:30`);
        const resolved = resolveZoned(entry.zone, d(entry.spring), time);
        expect(Number.isFinite(resolved.instant.epochMs)).toBe(true);
        expect(['exact', 'skipped', 'ambiguous']).toContain(resolved.outcome);
      }
    }
  });

  it('never resolve a reading onto the day before', () => {
    for (const entry of TRICKY) {
      for (const day of [entry.spring, entry.autumn]) {
        for (const hour of ['00:30', '01:30', '02:30', '03:30', '23:30']) {
          const at = zonedInstant(entry.zone, d(day), t(hour));
          const read = formatCivilDate(civilDateIn(entry.zone, at));
          expect(read >= day).toBe(true);
        }
      }
    }
  });

  it('keep midnight on the day it belongs to', () => {
    for (const entry of TRICKY) {
      for (const day of [entry.spring, entry.autumn]) {
        const at = zonedInstant(entry.zone, d(day), t('00:00'));
        expect(formatCivilDate(civilDateIn(entry.zone, at))).toBe(day);
      }
    }
  });
});

describe('a rotation across a transition', () => {
  it('puts a check in on the shift day it was made on', () => {
    // The clocks move in Oslo overnight on the thirtieth of March, and this
    // rota has that day as a shift day, so it is measured on the site clock.
    const onShift = aMember({
      homeZone: 'Europe/Lisbon',
      siteZone: 'Europe/Oslo',
      anchor: '2025-03-24',
    });
    const day = rotationDayAt(onShift, parseInstant('2025-03-30T05:00:00Z'));
    expect(formatCivilDate(day.date)).toBe('2025-03-30');
    expect(day.zone).toBe('Europe/Oslo');
  });

  it('reads the same instant differently on the two clocks', () => {
    const at = parseInstant('2025-03-29T23:30:00Z');
    expect(formatCivilDate(civilDateIn('Europe/Oslo', at))).toBe('2025-03-30');
    expect(formatCivilDate(civilDateIn('Europe/Lisbon', at))).toBe('2025-03-29');
  });

  it('scores a fortnight over a transition without losing or doubling a day', () => {
    resetCheckInIds();
    const member = aMember({ siteZone: 'Europe/Oslo', anchor: '2025-03-24' });
    const habit = aHabit({ cadence: '1/onshift-day', startsOn: '2025-03-24' });
    const window = parseDateRange('2025-03-24..2025-04-06');
    const obligations = expandHabit({ roster: roster(member), habit, window });
    expect(obligations).toHaveLength(14);

    const entries = obligations.map((entry) =>
      aCheckIn(`${formatCivilDate(entry.dueOn)}T09:00:00Z`),
    );
    const results = evaluateAll(habit, obligations, resolveAll(member, entries), d('2025-04-07'));
    expect(results.filter((result) => result.status === 'met')).toHaveLength(14);
  });
});

describe('the half hour and three quarter hour zones', () => {
  const odd = ['Asia/Kolkata', 'Asia/Kathmandu', 'Australia/Adelaide', 'Pacific/Chatham'];

  it('round trip a wall clock reading', () => {
    for (const zone of odd) {
      const at = zonedInstant(zone, d('2025-06-15'), t('07:30'));
      expect(formatCivilDate(civilDateIn(zone, at))).toBe('2025-06-15');
    }
  });

  it('put a late check in on the right rotation day', () => {
    for (const zone of odd) {
      const member = aMember({ homeZone: zone, siteZone: zone });
      const at = zonedInstant(zone, d('2025-06-15'), t('23:45'));
      expect(formatCivilDate(rotationDayAt(member, at).date)).toBe('2025-06-15');
    }
  });
});

describe('a crew that flies between hemispheres', () => {
  it('keeps the shift day on the site clock and the rest day at home', () => {
    const member = aMember({ homeZone: 'Pacific/Auckland', siteZone: 'America/Anchorage' });
    const onShift = rotationDayAt(member, parseInstant('2025-01-10T02:00:00Z'));
    expect(onShift.zone).toBe('America/Anchorage');
    expect(formatCivilDate(onShift.date)).toBe('2025-01-09');

    const atHome = rotationDayAt(member, parseInstant('2025-01-25T02:00:00Z'));
    expect(atHome.zone).toBe('Pacific/Auckland');
    expect(formatCivilDate(atHome.date)).toBe('2025-01-25');
  });
});
