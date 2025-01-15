import { NotFoundError } from '../domain/errors.js';
import {
  checkOneCrewPerDay,
  crew,
  crewOf,
  endMembership,
  membersOn,
  membership,
  type Crew,
  type CrewRole,
  type Membership,
} from '../domain/crew/crew.js';
import type { CrewMember } from '../domain/crew/member.js';
import {
  canRead,
  sharingPreference,
  setHabitVisibility,
  type SharingPreference,
  type Visibility,
} from '../domain/crew/sharing.js';
import {
  crewId as parseCrewId,
  type CrewId,
  type CrewMemberId,
  type HabitId,
} from '../domain/ids.js';
import { parseCivilDate, type CivilDate } from '../domain/time/civil.js';
import type { Ports } from './ports.js';

export interface CreateCrewCommand {
  readonly name: string;
  readonly site: string;
  readonly siteZone: string;
}

export interface EnrolCommand {
  readonly crewId: CrewId;
  readonly memberId: CrewMemberId;
  readonly from: string;
  readonly role?: CrewRole;
}

export interface CrewService {
  create(command: CreateCrewCommand): Promise<Crew>;
  enrol(command: EnrolCommand): Promise<Membership>;
  release(crewId: CrewId, memberId: CrewMemberId, on: string): Promise<Membership>;
  membersOfCrew(crewId: CrewId, on: string): Promise<CrewMember[]>;
  crewFor(memberId: CrewMemberId, on: string): Promise<Crew | undefined>;
  visibilityFor(memberId: CrewMemberId): Promise<SharingPreference>;
  setVisibility(memberId: CrewMemberId, fallback: Visibility): Promise<SharingPreference>;
  setHabitVisibility(
    memberId: CrewMemberId,
    habitId: HabitId,
    visibility: Visibility,
  ): Promise<SharingPreference>;
  mayRead(
    viewerId: CrewMemberId,
    subjectId: CrewMemberId,
    habitId: HabitId,
    on: string,
  ): Promise<boolean>;
}

/**
 * Crews, who is in them and who may read whose numbers. The sharing rules live
 * next to the membership rules on purpose: every access question needs both.
 */
export function crewService(ports: Ports): CrewService {
  async function requireMember(memberId: CrewMemberId): Promise<CrewMember> {
    const found = await ports.members.byId(memberId);
    if (found === undefined) throw new NotFoundError('crew member', memberId);
    return found;
  }

  async function preferenceOf(memberId: CrewMemberId): Promise<SharingPreference> {
    const stored = await ports.sharing.byMember(memberId);
    return stored ?? sharingPreference({ memberId });
  }

  async function roleOn(memberId: CrewMemberId, on: CivilDate): Promise<CrewRole> {
    const rows = await ports.crews.memberships();
    return crewOf(rows, memberId, on)?.role ?? 'member';
  }

  return {
    async create(command: CreateCrewCommand): Promise<Crew> {
      const entry = crew({
        id: parseCrewId(ports.ids.next('CrewId')),
        name: command.name,
        site: command.site,
        siteZone: command.siteZone,
      });
      await ports.crews.save(entry);
      return entry;
    },

    async enrol(command: EnrolCommand): Promise<Membership> {
      await requireMember(command.memberId);
      const target = await ports.crews.byId(command.crewId);
      if (target === undefined) throw new NotFoundError('crew', command.crewId);
      const entry = membership({
        crewId: command.crewId,
        memberId: command.memberId,
        from: parseCivilDate(command.from),
        role: command.role,
      });
      checkOneCrewPerDay([...(await ports.crews.memberships()), entry]);
      await ports.crews.addMembership(entry);
      return entry;
    },

    async release(crewId: CrewId, memberId: CrewMemberId, on: string): Promise<Membership> {
      const rows = await ports.crews.memberships();
      const held = rows.find((row) => row.crewId === crewId && row.memberId === memberId);
      if (held === undefined) throw new NotFoundError('membership', memberId);
      const ended = endMembership(held, parseCivilDate(on));
      await ports.crews.replaceMembership(ended);
      return ended;
    },

    async membersOfCrew(crewId: CrewId, on: string): Promise<CrewMember[]> {
      const rows = membersOn(await ports.crews.memberships(), crewId, parseCivilDate(on));
      const found: CrewMember[] = [];
      for (const row of rows) {
        const member = await ports.members.byId(row.memberId);
        if (member !== undefined) found.push(member);
      }
      return found.sort((left, right) => left.displayName.localeCompare(right.displayName));
    },

    async crewFor(memberId: CrewMemberId, on: string): Promise<Crew | undefined> {
      const row = crewOf(await ports.crews.memberships(), memberId, parseCivilDate(on));
      if (row === undefined) return undefined;
      return ports.crews.byId(row.crewId);
    },

    visibilityFor: preferenceOf,

    async setVisibility(memberId: CrewMemberId, fallback: Visibility): Promise<SharingPreference> {
      await requireMember(memberId);
      const current = await preferenceOf(memberId);
      const updated = sharingPreference({ memberId, fallback, perHabit: current.perHabit });
      await ports.sharing.save(updated);
      return updated;
    },

    async setHabitVisibility(
      memberId: CrewMemberId,
      habitId: HabitId,
      visibility: Visibility,
    ): Promise<SharingPreference> {
      await requireMember(memberId);
      const updated = setHabitVisibility(await preferenceOf(memberId), habitId, visibility);
      await ports.sharing.save(updated);
      return updated;
    },

    async mayRead(
      viewerId: CrewMemberId,
      subjectId: CrewMemberId,
      habitId: HabitId,
      on: string,
    ): Promise<boolean> {
      const date = parseCivilDate(on);
      return canRead({
        viewer: { memberId: viewerId, role: await roleOn(viewerId, date) },
        preference: await preferenceOf(subjectId),
        habitId,
        memberships: await ports.crews.memberships(),
        on: date,
      });
    },
  };
}
