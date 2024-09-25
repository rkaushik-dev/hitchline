import { describe, expect, it } from 'vitest';
import {
  civilDateIn,
  clockTimeIn,
  describeZoned,
  formatInstant,
  instant,
  isKnownZone,
  offsetMinutesAt,
  parseInstant,
  resolveZoned,
  startOfDayIn,
  zoneId,
  zonedInstant,
} from '../../../../src/domain/time/zone.js';
import { parseCivilDate } from '../../../../src/domain/time/civil.js';
import { parseClockTime } from '../../../../src/domain/time/clocktime.js';

const d = parseCivilDate;
const t = parseClockTime;

describe('zone ids', () => {
  it('accepts zones the platform knows', () => {
    expect(isKnownZone('Europe/Oslo')).toBe(true);
    expect(isKnownZone('America/St_Johns')).toBe(true);
    expect(isKnownZone('UTC')).toBe(true);
  });

  it('rejects invented zones', () => {
    expect(isKnownZone('Sea/Platform_Alpha')).toBe(false);
    expect(() => zoneId('Sea/Platform_Alpha')).toThrow(/not a timezone/);
  });
});

describe('instants', () => {
  it('parses and formats an iso timestamp', () => {
    expect(formatInstant(parseInstant('2025-03-30T00:30:00Z'))).toBe('2025-03-30T00:30:00.000Z');
  });

  it('refuses a timestamp it cannot read', () => {
    expect(() => parseInstant('half past march')).toThrow(/iso timestamp/);
  });

  it('refuses a non finite epoch', () => {
    expect(() => instant(Number.POSITIVE_INFINITY)).toThrow(/finite number/);
  });
});

describe('offsets', () => {
  it('reads a whole hour offset', () => {
    expect(offsetMinutesAt('Europe/Oslo', parseInstant('2025-01-15T12:00:00Z'))).toBe(60);
    expect(offsetMinutesAt('Europe/Oslo', parseInstant('2025-07-15T12:00:00Z'))).toBe(120);
  });

  it('reads a half hour offset', () => {
    expect(offsetMinutesAt('Asia/Kolkata', parseInstant('2025-01-15T12:00:00Z'))).toBe(330);
  });

  it('reads a negative offset', () => {
    expect(offsetMinutesAt('America/Halifax', parseInstant('2025-01-15T12:00:00Z'))).toBe(-240);
  });

  it('reads the forty five minute offset in Kathmandu', () => {
    expect(offsetMinutesAt('Asia/Kathmandu', parseInstant('2025-01-15T12:00:00Z'))).toBe(345);
  });
});

describe('reading a wall clock in a zone', () => {
  it('gives the local date, which can differ from the utc one', () => {
    const at = parseInstant('2025-01-15T23:30:00Z');
    expect(civilDateIn('Europe/Oslo', at)).toEqual(d('2025-01-16'));
    expect(civilDateIn('America/Halifax', at)).toEqual(d('2025-01-15'));
  });

  it('gives the local time', () => {
    const at = parseInstant('2025-06-01T12:00:00Z');
    expect(clockTimeIn('Europe/Oslo', at)).toEqual(t('14:00'));
    expect(clockTimeIn('UTC', at)).toEqual(t('12:00'));
  });

  it('describes an instant with its offset', () => {
    expect(describeZoned('Asia/Kolkata', parseInstant('2025-01-15T12:00:00Z'))).toBe(
      '2025-01-15 17:30+05:30',
    );
    expect(describeZoned('America/Halifax', parseInstant('2025-01-15T12:00:00Z'))).toBe(
      '2025-01-15 08:00-04:00',
    );
  });
});

describe('resolving a wall clock reading back to an instant', () => {
  it('is exact on an ordinary day', () => {
    const resolved = resolveZoned('Europe/Oslo', d('2025-06-01'), t('07:00'));
    expect(resolved.outcome).toBe('exact');
    expect(formatInstant(resolved.instant)).toBe('2025-06-01T05:00:00.000Z');
    expect(resolved.offsetMinutes).toBe(120);
  });

  it('slides a time inside the spring gap forward past it', () => {
    const resolved = resolveZoned('Europe/London', d('2025-03-30'), t('01:30'));
    expect(resolved.outcome).toBe('skipped');
    expect(formatInstant(resolved.instant)).toBe('2025-03-30T01:30:00.000Z');
    expect(clockTimeIn('Europe/London', resolved.instant)).toEqual(t('02:30'));
  });

  it('takes the first pass through an autumn overlap', () => {
    const resolved = resolveZoned('Europe/London', d('2025-10-26'), t('01:30'));
    expect(resolved.outcome).toBe('ambiguous');
    expect(formatInstant(resolved.instant)).toBe('2025-10-26T00:30:00.000Z');
    expect(resolved.offsetMinutes).toBe(60);
  });

  it('is exact either side of a transition', () => {
    expect(resolveZoned('Europe/London', d('2025-03-30'), t('00:30')).outcome).toBe('exact');
    expect(resolveZoned('Europe/London', d('2025-03-30'), t('03:30')).outcome).toBe('exact');
  });

  it('handles a zone that never changes offset', () => {
    const resolved = resolveZoned('Asia/Kolkata', d('2025-03-30'), t('01:30'));
    expect(resolved.outcome).toBe('exact');
    expect(formatInstant(resolved.instant)).toBe('2025-03-29T20:00:00.000Z');
  });

  it('round trips through the zone it came from', () => {
    const at = zonedInstant('America/Halifax', d('2025-08-08'), t('06:15'));
    expect(civilDateIn('America/Halifax', at)).toEqual(d('2025-08-08'));
    expect(clockTimeIn('America/Halifax', at)).toEqual(t('06:15'));
  });
});

describe('startOfDayIn', () => {
  it('lands on local midnight', () => {
    const at = startOfDayIn('Europe/Oslo', d('2025-06-01'));
    expect(formatInstant(at)).toBe('2025-05-31T22:00:00.000Z');
    expect(clockTimeIn('Europe/Oslo', at)).toEqual(t('00:00'));
  });

  it('copes with a zone whose day starts inside a transition', () => {
    const at = startOfDayIn('America/Santiago', d('2025-09-07'));
    expect(civilDateIn('America/Santiago', at)).toEqual(d('2025-09-07'));
  });
});
