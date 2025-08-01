import { ValidationError } from '../errors.js';
import type { CrewMemberId } from '../ids.js';
import { parseClockRange, formatClockRange, type ClockRange } from '../time/clocktime.js';
import { isKnownZone, type ZoneId } from '../time/zone.js';

/**
 * The choices a person makes about their own account. None of it changes what
 * they are measured against, which is the point: a preference that quietly
 * moved the goalposts would make two crew members incomparable.
 */
export type DigestFrequency = 'none' | 'hitch' | 'week';
export type DistanceUnit = 'kilometres' | 'miles';

export interface MemberPreferences {
  readonly memberId: CrewMemberId;
  readonly digest: DigestFrequency;
  readonly distance: DistanceUnit;
  /** Overrides the operator quiet hours, but only to make them wider. */
  readonly quietHours: ClockRange | undefined;
  readonly displayZone: ZoneId | undefined;
}

export interface PreferencesInput {
  readonly memberId: CrewMemberId;
  readonly digest?: DigestFrequency;
  readonly distance?: DistanceUnit;
  readonly quietHours?: string;
  readonly displayZone?: string;
}

const FREQUENCIES: readonly DigestFrequency[] = ['none', 'hitch', 'week'];
const UNITS: readonly DistanceUnit[] = ['kilometres', 'miles'];

export function defaultPreferences(memberId: CrewMemberId): MemberPreferences {
  return {
    memberId,
    digest: 'hitch',
    distance: 'kilometres',
    quietHours: undefined,
    displayZone: undefined,
  };
}

export function memberPreferences(input: PreferencesInput): MemberPreferences {
  const digest = input.digest ?? 'hitch';
  if (!FREQUENCIES.includes(digest)) {
    throw ValidationError.field('digest', `must be one of ${FREQUENCIES.join(', ')}`);
  }
  const distance = input.distance ?? 'kilometres';
  if (!UNITS.includes(distance)) {
    throw ValidationError.field('distance', `must be one of ${UNITS.join(', ')}`);
  }
  if (input.displayZone !== undefined && !isKnownZone(input.displayZone)) {
    throw ValidationError.field('displayZone', `is not a timezone: ${input.displayZone}`);
  }
  return {
    memberId: input.memberId,
    digest,
    distance,
    quietHours: input.quietHours === undefined ? undefined : parseClockRange(input.quietHours),
    displayZone: input.displayZone,
  };
}

export function wantsDigest(preferences: MemberPreferences): boolean {
  return preferences.digest !== 'none';
}

const KILOMETRES_TO_MILES = 0.621_371;

/** Converts a reading held in kilometres for display, never for scoring. */
export function forDisplay(preferences: MemberPreferences, kilometres: number): number {
  if (preferences.distance === 'kilometres') return Math.round(kilometres * 100) / 100;
  return Math.round(kilometres * KILOMETRES_TO_MILES * 100) / 100;
}

export function unitLabel(preferences: MemberPreferences): string {
  return preferences.distance === 'miles' ? 'mi' : 'km';
}

/** The zone a person wants to read reports in, which is their home by default. */
export function readingZone(preferences: MemberPreferences, homeZone: ZoneId): ZoneId {
  return preferences.displayZone ?? homeZone;
}

export function describePreferences(preferences: MemberPreferences): string {
  const quiet =
    preferences.quietHours === undefined
      ? 'operator quiet hours'
      : formatClockRange(preferences.quietHours);
  return `${preferences.digest} digest, ${preferences.distance}, quiet ${quiet}`;
}
