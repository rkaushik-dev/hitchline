import { describe, expect, it } from 'vitest';
import {
  canRead,
  clearHabitVisibility,
  describeVisibility,
  isVisibility,
  readableHabits,
  requireRead,
  setHabitVisibility,
  sharingPreference,
  visibilityFor,
  type Viewer,
} from '../../../../src/domain/crew/sharing.js';
import { membership } from '../../../../src/domain/crew/crew.js';
import { crewId, crewMemberId, habitId } from '../../../../src/domain/ids.js';
import { parseCivilDate } from '../../../../src/domain/time/civil.js';
import { ForbiddenError } from '../../../../src/domain/errors.js';

const d = parseCivilDate;
const alpha = crewId('crw_0123456789ABCDEF');
const bravo = crewId('crw_FEDCBA9876543210');
const ada = crewMemberId('mem_0123456789ABCDEF');
const bo = crewMemberId('mem_FEDCBA9876543210');
const cleo = crewMemberId('mem_1111111111111111');
const treadmill = habitId('hab_0123456789ABCDEF');
const sleep = habitId('hab_FEDCBA9876543210');
const on = d('2025-02-15');

const memberships = [
  membership({ crewId: alpha, memberId: ada, from: d('2025-01-01') }),
  membership({ crewId: alpha, memberId: bo, from: d('2025-01-01'), role: 'lead' }),
  membership({ crewId: alpha, memberId: cleo, from: d('2025-01-01') }),
];

const asMember: Viewer = { memberId: cleo, role: 'member' };
const asLead: Viewer = { memberId: bo, role: 'lead' };

function request(fallback: 'private' | 'lead_only' | 'crew', viewer: Viewer) {
  return {
    viewer,
    preference: sharingPreference({ memberId: ada, fallback }),
    habitId: treadmill,
    memberships,
    on,
  };
}

describe('visibility values', () => {
  it('knows the three settings', () => {
    expect(isVisibility('crew')).toBe(true);
    expect(isVisibility('everyone')).toBe(false);
  });

  it('describes each one in plain words', () => {
    expect(describeVisibility('private')).toBe('only you');
    expect(describeVisibility('lead_only')).toBe('you and your crew lead');
    expect(describeVisibility('crew')).toBe('everybody on your crew');
  });
});

describe('preferences', () => {
  it('defaults to private', () => {
    expect(sharingPreference({ memberId: ada }).fallback).toBe('private');
  });

  it('lets one habit differ from the fallback', () => {
    const preference = setHabitVisibility(
      sharingPreference({ memberId: ada, fallback: 'private' }),
      treadmill,
      'crew',
    );
    expect(visibilityFor(preference, treadmill)).toBe('crew');
    expect(visibilityFor(preference, sleep)).toBe('private');
  });

  it('drops back to the fallback when the override is cleared', () => {
    const preference = setHabitVisibility(
      sharingPreference({ memberId: ada, fallback: 'lead_only' }),
      treadmill,
      'crew',
    );
    expect(visibilityFor(clearHabitVisibility(preference, treadmill), treadmill)).toBe('lead_only');
  });

  it('refuses a setting it does not know', () => {
    expect(() => sharingPreference({ memberId: ada, fallback: 'public' as never })).toThrow(
      /is not a visibility/,
    );
  });
});

describe('who may read', () => {
  it('always lets somebody read their own', () => {
    const own: Viewer = { memberId: ada, role: 'member' };
    expect(canRead(request('private', own))).toBe(true);
  });

  it('keeps a private habit from the crew and the lead', () => {
    expect(canRead(request('private', asMember))).toBe(false);
    expect(canRead(request('private', asLead))).toBe(false);
  });

  it('opens a lead only habit to the lead but not to a crewmate', () => {
    expect(canRead(request('lead_only', asLead))).toBe(true);
    expect(canRead(request('lead_only', asMember))).toBe(false);
  });

  it('opens a crew habit to everybody on the crew', () => {
    expect(canRead(request('crew', asMember))).toBe(true);
    expect(canRead(request('crew', asLead))).toBe(true);
  });

  it('shuts out somebody on another crew whatever the setting', () => {
    const outsider: Viewer = { memberId: crewMemberId('mem_2222222222222222'), role: 'lead' };
    const withOutsider = {
      ...request('crew', outsider),
      memberships: [
        ...memberships,
        membership({
          crewId: bravo,
          memberId: outsider.memberId,
          from: d('2025-01-01'),
          role: 'lead',
        }),
      ],
    };
    expect(canRead(withOutsider)).toBe(false);
  });

  it('shuts out a viewer who was in no crew that day', () => {
    const early = { ...request('crew', asMember), on: d('2024-06-01') };
    expect(canRead(early)).toBe(false);
  });

  it('reads the crew as it was on the day asked about', () => {
    const later = {
      ...request('crew', asMember),
      memberships: [
        membership({ crewId: alpha, memberId: ada, from: d('2025-01-01'), to: d('2025-01-31') }),
        membership({ crewId: alpha, memberId: cleo, from: d('2025-01-01') }),
      ],
    };
    expect(canRead(later)).toBe(false);
  });
});

describe('requireRead', () => {
  it('passes quietly when allowed', () => {
    expect(() => requireRead(request('crew', asMember))).not.toThrow();
  });

  it('throws with both names when not', () => {
    expect(() => requireRead(request('private', asMember))).toThrow(ForbiddenError);
    expect(() => requireRead(request('private', asMember))).toThrow(new RegExp(ada));
  });
});

describe('readableHabits', () => {
  it('filters a list down to what the viewer may see', () => {
    const preference = setHabitVisibility(
      sharingPreference({ memberId: ada, fallback: 'private' }),
      treadmill,
      'crew',
    );
    const readable = readableHabits({ viewer: asMember, preference, memberships, on }, [
      treadmill,
      sleep,
    ]);
    expect(readable).toEqual([treadmill]);
  });
});
