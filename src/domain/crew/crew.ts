import { ConflictError, NotFoundError, ValidationError } from '../errors.js';
import type { CrewId, CrewMemberId } from '../ids.js';
import { compareCivil, isBefore, type CivilDate } from '../time/civil.js';
import { zoneId, type ZoneId } from '../time/zone.js';

/**
 * A crew is the unit a supervisor reads: everybody working one installation on
 * one rota. Membership is dated, because people move between crews and a report
 * about last March has to use the crew somebody was in at the time.
 */
export type CrewRole = 'member' | 'lead' | 'coordinator';

export interface Crew {
  readonly id: CrewId;
  readonly name: string;
  readonly site: string;
  readonly siteZone: ZoneId;
}

export interface Membership {
  readonly crewId: CrewId;
  readonly memberId: CrewMemberId;
  readonly role: CrewRole;
  readonly from: CivilDate;
  readonly to: CivilDate | undefined;
}

export interface CrewInput {
  readonly id: CrewId;
  readonly name: string;
  readonly site: string;
  readonly siteZone: string;
}

export interface MembershipInput {
  readonly crewId: CrewId;
  readonly memberId: CrewMemberId;
  readonly from: CivilDate;
  readonly role?: CrewRole;
  readonly to?: CivilDate;
}

const NAME_MIN = 2;
const NAME_MAX = 60;
const SITE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9 .-]{1,39}$/;

export function crew(input: CrewInput): Crew {
  const name = input.name.replace(/\s+/g, ' ').trim();
  if (name.length < NAME_MIN || name.length > NAME_MAX) {
    throw ValidationError.field('name', `must be ${NAME_MIN} to ${NAME_MAX} characters`);
  }
  const site = input.site.trim();
  if (!SITE_PATTERN.test(site)) {
    throw ValidationError.field('site', `is not a usable site name: ${input.site}`);
  }
  return { id: input.id, name, site, siteZone: zoneId(input.siteZone) };
}

export function membership(input: MembershipInput): Membership {
  if (input.to !== undefined && isBefore(input.to, input.from)) {
    throw ValidationError.field('to', 'ends before the membership starts');
  }
  return {
    crewId: input.crewId,
    memberId: input.memberId,
    role: input.role ?? 'member',
    from: input.from,
    to: input.to,
  };
}

function covers(entry: Membership, date: CivilDate): boolean {
  if (compareCivil(date, entry.from) < 0) return false;
  if (entry.to === undefined) return true;
  return compareCivil(date, entry.to) <= 0;
}

export function membersOn(
  memberships: readonly Membership[],
  crewId: CrewId,
  date: CivilDate,
): Membership[] {
  return memberships.filter((entry) => entry.crewId === crewId && covers(entry, date));
}

export function crewOf(
  memberships: readonly Membership[],
  memberId: CrewMemberId,
  date: CivilDate,
): Membership | undefined {
  return memberships.find((entry) => entry.memberId === memberId && covers(entry, date));
}

/** Nobody may be in two crews on the same day. */
export function checkOneCrewPerDay(memberships: readonly Membership[]): void {
  const byMember = new Map<CrewMemberId, Membership[]>();
  for (const entry of memberships) {
    const held = byMember.get(entry.memberId) ?? [];
    held.push(entry);
    byMember.set(entry.memberId, held);
  }
  for (const [memberId, held] of byMember) {
    for (let index = 0; index < held.length; index += 1) {
      for (let other = index + 1; other < held.length; other += 1) {
        const left = held[index];
        const right = held[other];
        if (left === undefined || right === undefined) continue;
        if (overlaps(left, right)) {
          throw new ConflictError(`${memberId} is in two crews at once`);
        }
      }
    }
  }
}

function overlaps(left: Membership, right: Membership): boolean {
  const leftEnd = left.to;
  const rightEnd = right.to;
  if (leftEnd !== undefined && compareCivil(right.from, leftEnd) > 0) return false;
  if (rightEnd !== undefined && compareCivil(left.from, rightEnd) > 0) return false;
  return true;
}

export function leadsOf(
  memberships: readonly Membership[],
  crewId: CrewId,
  date: CivilDate,
): Membership[] {
  return membersOn(memberships, crewId, date).filter((entry) => entry.role !== 'member');
}

export function requireCrew(crews: readonly Crew[], crewId: CrewId): Crew {
  const found = crews.find((entry) => entry.id === crewId);
  if (found === undefined) throw new NotFoundError('crew', crewId);
  return found;
}

export function endMembership(entry: Membership, on: CivilDate): Membership {
  if (compareCivil(on, entry.from) < 0) {
    throw ValidationError.field('to', 'is before the membership started');
  }
  return { ...entry, to: on };
}
