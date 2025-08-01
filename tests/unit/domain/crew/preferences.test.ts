import { describe, expect, it } from 'vitest';
import {
  defaultPreferences,
  describePreferences,
  forDisplay,
  memberPreferences,
  readingZone,
  unitLabel,
  wantsDigest,
} from '../../../../src/domain/crew/preferences.js';
import { crewMemberId } from '../../../../src/domain/ids.js';
import { ValidationError } from '../../../../src/domain/errors.js';

const ada = crewMemberId('mem_0123456789ABCDEF');

describe('defaults', () => {
  it('sends a digest at the end of each hitch in kilometres', () => {
    const preferences = defaultPreferences(ada);
    expect(preferences.digest).toBe('hitch');
    expect(preferences.distance).toBe('kilometres');
    expect(preferences.quietHours).toBeUndefined();
    expect(wantsDigest(preferences)).toBe(true);
  });

  it('reads the same as building one with nothing set', () => {
    expect(memberPreferences({ memberId: ada })).toEqual(defaultPreferences(ada));
  });
});

describe('building preferences', () => {
  it('takes a frequency and a unit', () => {
    const preferences = memberPreferences({ memberId: ada, digest: 'week', distance: 'miles' });
    expect(preferences.digest).toBe('week');
    expect(preferences.distance).toBe('miles');
  });

  it('refuses a frequency or a unit it does not know', () => {
    expect(() => memberPreferences({ memberId: ada, digest: 'daily' as never })).toThrow(
      ValidationError,
    );
    expect(() => memberPreferences({ memberId: ada, distance: 'furlongs' as never })).toThrow(
      /kilometres, miles/,
    );
  });

  it('reads quiet hours from a string', () => {
    const preferences = memberPreferences({ memberId: ada, quietHours: '21:00-07:00' });
    expect(preferences.quietHours?.from.hour).toBe(21);
  });

  it('refuses quiet hours it cannot read', () => {
    expect(() => memberPreferences({ memberId: ada, quietHours: 'evenings' })).toThrow(
      /HH:MM-HH:MM/,
    );
  });

  it('checks a display zone against the platform', () => {
    expect(memberPreferences({ memberId: ada, displayZone: 'Europe/Oslo' }).displayZone).toBe(
      'Europe/Oslo',
    );
    expect(() => memberPreferences({ memberId: ada, displayZone: 'North Sea/Alpha' })).toThrow(
      /is not a timezone/,
    );
  });
});

describe('digests', () => {
  it('can be turned off', () => {
    expect(wantsDigest(memberPreferences({ memberId: ada, digest: 'none' }))).toBe(false);
  });
});

describe('units for display', () => {
  const metric = defaultPreferences(ada);
  const imperial = memberPreferences({ memberId: ada, distance: 'miles' });

  it('leaves kilometres alone', () => {
    expect(forDisplay(metric, 5)).toBe(5);
    expect(unitLabel(metric)).toBe('km');
  });

  it('converts for somebody who reads miles', () => {
    expect(forDisplay(imperial, 5)).toBe(3.11);
    expect(unitLabel(imperial)).toBe('mi');
  });

  it('rounds to two places either way', () => {
    expect(forDisplay(metric, 5.238)).toBe(5.24);
    expect(forDisplay(imperial, 10)).toBe(6.21);
  });
});

describe('the reading zone', () => {
  it('is the home zone unless somebody chose another', () => {
    expect(readingZone(defaultPreferences(ada), 'Europe/Lisbon')).toBe('Europe/Lisbon');
    const chosen = memberPreferences({ memberId: ada, displayZone: 'Europe/Oslo' });
    expect(readingZone(chosen, 'Europe/Lisbon')).toBe('Europe/Oslo');
  });
});

describe('describing them', () => {
  it('reads back in one line', () => {
    expect(describePreferences(defaultPreferences(ada))).toBe(
      'hitch digest, kilometres, quiet operator quiet hours',
    );
    const own = memberPreferences({ memberId: ada, quietHours: '21:00-07:00' });
    expect(describePreferences(own)).toContain('quiet 21:00-07:00');
  });
});
