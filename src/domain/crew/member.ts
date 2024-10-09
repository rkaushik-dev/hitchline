import { ValidationError } from '../errors.js';
import type { CrewMemberId } from '../ids.js';
import type { CivilDate } from '../time/civil.js';
import { zoneId, type ZoneId } from '../time/zone.js';
import type { RotationAssignment } from '../rotation/assignment.js';

/** Where a member sits with the operator. Only an active member owes anything. */
export type MemberStatus = 'active' | 'suspended' | 'departed';

export interface CrewMember {
  readonly id: CrewMemberId;
  readonly displayName: string;
  /** Where they live, which is where an off shift day is measured. */
  readonly homeZone: ZoneId;
  /** Where they work, which is where an on shift day is measured. */
  readonly siteZone: ZoneId;
  readonly rotation: RotationAssignment;
  readonly status: MemberStatus;
  readonly joinedOn: CivilDate;
}

export interface CrewMemberInput {
  readonly id: CrewMemberId;
  readonly displayName: string;
  readonly homeZone: string;
  readonly siteZone: string;
  readonly rotation: RotationAssignment;
  readonly joinedOn: CivilDate;
  readonly status?: MemberStatus;
}

const NAME_MIN = 2;
const NAME_MAX = 80;
// eslint-disable-next-line no-control-regex
const CONTROL_CHARS = /[\u0000-\u001f\u007f]/;

const ALLOWED_MOVES: Record<MemberStatus, readonly MemberStatus[]> = {
  active: ['suspended', 'departed'],
  suspended: ['active', 'departed'],
  departed: [],
};

export function normaliseName(raw: string): string {
  return raw.replace(/\s+/g, ' ').trim();
}

export function checkName(raw: string): string {
  if (CONTROL_CHARS.test(raw)) {
    throw ValidationError.field('displayName', 'must not contain control characters');
  }
  const name = normaliseName(raw);
  if (name.length < NAME_MIN) {
    throw ValidationError.field('displayName', `must be at least ${NAME_MIN} characters`);
  }
  if (name.length > NAME_MAX) {
    throw ValidationError.field('displayName', `must be at most ${NAME_MAX} characters`);
  }
  return name;
}

export function crewMember(input: CrewMemberInput): CrewMember {
  return {
    id: input.id,
    displayName: checkName(input.displayName),
    homeZone: zoneId(input.homeZone),
    siteZone: zoneId(input.siteZone),
    rotation: input.rotation,
    status: input.status ?? 'active',
    joinedOn: input.joinedOn,
  };
}

export function isActiveMember(member: CrewMember): boolean {
  return member.status === 'active';
}

export function rename(member: CrewMember, displayName: string): CrewMember {
  return { ...member, displayName: checkName(displayName) };
}

export function moveTo(member: CrewMember, status: MemberStatus): CrewMember {
  if (member.status === status) return member;
  const allowed = ALLOWED_MOVES[member.status];
  if (!allowed.includes(status)) {
    throw ValidationError.field('status', `cannot go from ${member.status} to ${status}`);
  }
  return { ...member, status };
}

export function reassign(member: CrewMember, rotation: RotationAssignment): CrewMember {
  if (member.status === 'departed') {
    throw ValidationError.field('rotation', 'cannot be changed for somebody who has left');
  }
  return { ...member, rotation };
}

export function relocate(member: CrewMember, siteZone: string): CrewMember {
  return { ...member, siteZone: zoneId(siteZone) };
}

/** Two letters for a roster grid, taken from the first and last word of the name. */
export function initials(member: CrewMember): string {
  const words = member.displayName.split(' ').filter((word) => word.length > 0);
  const first = words[0] ?? '';
  const last = words.length > 1 ? (words[words.length - 1] ?? '') : '';
  return `${first.slice(0, 1).toUpperCase()}${last.slice(0, 1).toUpperCase()}`;
}
