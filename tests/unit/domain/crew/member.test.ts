import { describe, expect, it } from 'vitest';
import {
  checkName,
  crewMember,
  initials,
  isActiveMember,
  moveTo,
  normaliseName,
  reassign,
  relocate,
  rename,
  type CrewMemberInput,
} from '../../../../src/domain/crew/member.js';
import { crewMemberId } from '../../../../src/domain/ids.js';
import { parseCivilDate } from '../../../../src/domain/time/civil.js';
import { rotationAssignment } from '../../../../src/domain/rotation/assignment.js';
import { parseRotationPattern } from '../../../../src/domain/rotation/pattern.js';

const fortnights = parseRotationPattern('14/14');
const rotation = rotationAssignment({ pattern: fortnights, anchor: parseCivilDate('2025-01-06') });

const input: CrewMemberInput = {
  id: crewMemberId('mem_0123456789ABCDEF'),
  displayName: 'Ada Vasquez',
  homeZone: 'Europe/Lisbon',
  siteZone: 'Europe/Oslo',
  rotation,
  joinedOn: parseCivilDate('2024-11-01'),
};

describe('names', () => {
  it('squeezes runs of whitespace', () => {
    expect(normaliseName('  Ada   Vasquez ')).toBe('Ada Vasquez');
  });

  it('rejects a name that is too short or too long', () => {
    expect(() => checkName('A')).toThrow(/at least 2 characters/);
    expect(() => checkName('x'.repeat(81))).toThrow(/at most 80 characters/);
  });

  it('rejects control characters', () => {
    expect(() => checkName('Ada\u0000Vasquez')).toThrow(/control characters/);
    expect(() => checkName('Ada\u0007Vasquez')).toThrow(/control characters/);
  });

  it('keeps letters outside the ascii range', () => {
    expect(checkName('Bjørn Håland')).toBe('Bjørn Håland');
  });
});

describe('creating a member', () => {
  it('defaults to active', () => {
    const member = crewMember(input);
    expect(member.status).toBe('active');
    expect(isActiveMember(member)).toBe(true);
  });

  it('keeps the home and site zones apart', () => {
    const member = crewMember(input);
    expect(member.homeZone).toBe('Europe/Lisbon');
    expect(member.siteZone).toBe('Europe/Oslo');
  });

  it('rejects a zone the platform does not know', () => {
    expect(() => crewMember({ ...input, siteZone: 'North Sea/Alpha' })).toThrow(/not a timezone/);
  });

  it('tidies the name on the way in', () => {
    expect(crewMember({ ...input, displayName: ' Ada  Vasquez ' }).displayName).toBe('Ada Vasquez');
  });
});

describe('status moves', () => {
  const member = crewMember(input);

  it('suspends and reinstates', () => {
    const suspended = moveTo(member, 'suspended');
    expect(suspended.status).toBe('suspended');
    expect(moveTo(suspended, 'active').status).toBe('active');
  });

  it('treats a move to the same status as a no op', () => {
    expect(moveTo(member, 'active')).toBe(member);
  });

  it('refuses to bring back somebody who has left', () => {
    const departed = moveTo(member, 'departed');
    expect(() => moveTo(departed, 'active')).toThrow(/cannot go from departed to active/);
  });
});

describe('edits', () => {
  const member = crewMember(input);

  it('renames', () => {
    expect(rename(member, 'Ada Nkemdirim').displayName).toBe('Ada Nkemdirim');
  });

  it('moves site', () => {
    expect(relocate(member, 'America/Halifax').siteZone).toBe('America/Halifax');
  });

  it('reassigns the rotation', () => {
    const swung = rotationAssignment({
      pattern: parseRotationPattern('2-2-3'),
      anchor: parseCivilDate('2025-03-01'),
    });
    expect(reassign(member, swung).rotation).toBe(swung);
  });

  it('refuses to reassign somebody who has left', () => {
    const departed = moveTo(member, 'departed');
    expect(() => reassign(departed, rotation)).toThrow(/somebody who has left/);
  });
});

describe('initials', () => {
  it('takes the first and last word', () => {
    expect(initials(crewMember(input))).toBe('AV');
  });

  it('handles a three part name', () => {
    expect(initials(crewMember({ ...input, displayName: 'Ada Maria Vasquez' }))).toBe('AV');
  });

  it('handles a single word name', () => {
    expect(initials(crewMember({ ...input, displayName: 'Ada' }))).toBe('A');
  });
});
