import { describe, expect, it } from 'vitest';
import {
  describeRotationDay,
  rotationDayAt,
  rotationDayOn,
  zoneOn,
} from '../../../../src/domain/rotation/zoning.js';
import { formatCivilDate, parseCivilDate } from '../../../../src/domain/time/civil.js';
import { parseInstant } from '../../../../src/domain/time/zone.js';
import { aMember } from '../../../support/fixtures.js';

const d = parseCivilDate;

describe('zoneOn', () => {
  const member = aMember({ pattern: '1travel 13on 1travel 13off', anchor: '2025-01-06' });

  it('uses the site clock on shift', () => {
    expect(zoneOn(member, d('2025-01-07'))).toEqual({ zone: 'Europe/Oslo', reason: 'site' });
  });

  it('uses the home clock off shift', () => {
    expect(zoneOn(member, d('2025-01-21'))).toEqual({ zone: 'Europe/Lisbon', reason: 'home' });
  });

  it('sends an outbound travel day to the site clock', () => {
    expect(zoneOn(member, d('2025-01-06'))).toEqual({ zone: 'Europe/Oslo', reason: 'outbound' });
  });

  it('sends an inbound travel day to the home clock', () => {
    expect(zoneOn(member, d('2025-01-20'))).toEqual({ zone: 'Europe/Lisbon', reason: 'inbound' });
  });

  it('looks past a second travel day before deciding', () => {
    const slow = aMember({ pattern: '2travel 12on 2travel 12off', anchor: '2025-01-06' });
    expect(zoneOn(slow, d('2025-01-06')).reason).toBe('outbound');
    expect(zoneOn(slow, d('2025-01-07')).reason).toBe('outbound');
    expect(zoneOn(slow, d('2025-01-20')).reason).toBe('inbound');
    expect(zoneOn(slow, d('2025-01-21')).reason).toBe('inbound');
  });

  it('treats standby as time at home', () => {
    const standby = aMember({ pattern: '7on 3standby 4off', anchor: '2025-01-06' });
    expect(zoneOn(standby, d('2025-01-14'))).toEqual({ zone: 'Europe/Lisbon', reason: 'home' });
  });
});

describe('rotationDayOn', () => {
  it('answers for a date without needing a clock', () => {
    const day = rotationDayOn(aMember(), d('2025-01-10'));
    expect(day.role).toBe('onshift');
    expect(day.zone).toBe('Europe/Oslo');
    expect(day.straddled).toBe(false);
  });
});

describe('rotationDayAt', () => {
  const member = aMember();

  it('places an ordinary on shift instant on the site day', () => {
    const day = rotationDayAt(member, parseInstant('2025-01-10T09:00:00Z'));
    expect(formatCivilDate(day.date)).toBe('2025-01-10');
    expect(day.zone).toBe('Europe/Oslo');
    expect(day.straddled).toBe(false);
  });

  it('places an off shift instant on the home day', () => {
    const day = rotationDayAt(member, parseInstant('2025-01-25T09:00:00Z'));
    expect(formatCivilDate(day.date)).toBe('2025-01-25');
    expect(day.zone).toBe('Europe/Lisbon');
    expect(day.reason).toBe('home');
  });

  it('marks a day the two clocks disagree about', () => {
    // 23:30 UTC is already the 26th in Oslo but still the 25th in Lisbon, and
    // the 25th is an off shift day, so the home clock settles it.
    const day = rotationDayAt(member, parseInstant('2025-01-25T23:30:00Z'));
    expect(formatCivilDate(day.date)).toBe('2025-01-25');
    expect(day.zone).toBe('Europe/Lisbon');
    expect(day.straddled).toBe(true);
  });

  it('lets the site clock win when the readings pull apart', () => {
    // Late on the last night of the hitch, Oslo has already turned over into
    // the first off shift day while Lisbon is still on the last shift day.
    const day = rotationDayAt(member, parseInstant('2025-01-19T23:30:00Z'));
    expect(formatCivilDate(day.date)).toBe('2025-01-20');
    expect(day.zone).toBe('Europe/Oslo');
    expect(day.reason).toBe('site');
    expect(day.straddled).toBe(true);
  });

  it('is unaffected when home and site share a clock', () => {
    const local = aMember({ homeZone: 'Europe/Oslo', siteZone: 'Europe/Oslo' });
    const day = rotationDayAt(local, parseInstant('2025-01-25T23:30:00Z'));
    expect(formatCivilDate(day.date)).toBe('2025-01-26');
    expect(day.straddled).toBe(false);
  });

  it('handles a wide zone gap', () => {
    const far = aMember({ homeZone: 'Pacific/Auckland', siteZone: 'America/Anchorage' });
    const day = rotationDayAt(far, parseInstant('2025-01-10T02:00:00Z'));
    expect(formatCivilDate(day.date)).toBe('2025-01-09');
    expect(day.zone).toBe('America/Anchorage');
  });

  it('describes itself for a report line', () => {
    const day = rotationDayAt(member, parseInstant('2025-01-10T09:00:00Z'));
    expect(describeRotationDay(day)).toBe('2025-01-10 onshift on Europe/Oslo as site');
  });
});
