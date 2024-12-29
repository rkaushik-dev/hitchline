import { ConflictError, NotFoundError } from '../../domain/errors.js';
import type { CheckIn } from '../../domain/checkin/checkin.js';
import type { Crew, Membership } from '../../domain/crew/crew.js';
import type { CrewMember } from '../../domain/crew/member.js';
import type { SharingPreference } from '../../domain/crew/sharing.js';
import type { Habit } from '../../domain/habit/habit.js';
import type { CheckInId, CrewId, CrewMemberId, HabitId } from '../../domain/ids.js';
import type { NudgePolicy } from '../../domain/nudge/policy.js';
import type { RosterOverride } from '../../domain/rotation/override.js';
import type { Shield } from '../../domain/adherence/shield.js';
import { rangeContains, type DateRange } from '../../domain/time/range.js';
import { civilDateIn } from '../../domain/time/zone.js';
import type {
  CheckInRepository,
  Clock,
  CrewRepository,
  HabitRepository,
  IdSource,
  MemberRepository,
  OverrideRepository,
  PolicyRepository,
  Ports,
  SharingRepository,
  ShieldRepository,
} from '../../app/ports.js';
import { systemClock } from '../clock.js';
import { randomIds } from '../id.js';

/**
 * Repositories held in maps. This is the store the tests run against and the
 * one a demo runs on, and it is deliberately the same shape as the journal
 * backed one so a service cannot tell them apart.
 */
export interface MemoryStore extends Ports {
  reset(): void;
}

function memberRepository(members: Map<string, CrewMember>): MemberRepository {
  return {
    byId: async (id: CrewMemberId) => members.get(id),
    all: async () => [...members.values()],
    save: async (member: CrewMember) => {
      members.set(member.id, member);
    },
  };
}

function habitRepository(habits: Map<string, Habit>): HabitRepository {
  return {
    byId: async (id: HabitId) => habits.get(id),
    byMember: async (memberId: CrewMemberId) =>
      [...habits.values()].filter((habit) => habit.memberId === memberId),
    save: async (habit: Habit) => {
      habits.set(habit.id, habit);
    },
  };
}

function checkInRepository(
  entries: Map<string, CheckIn>,
  members: Map<string, CrewMember>,
): CheckInRepository {
  return {
    byId: async (id: CheckInId) => entries.get(id),
    // A coarse filter on the site clock. A service that needs the rotation day
    // resolves it properly through the zoning rules once the rows are loaded.
    byMember: async (memberId: CrewMemberId, window?: DateRange) => {
      const mine = [...entries.values()].filter((entry) => entry.memberId === memberId);
      if (window === undefined) return mine;
      const member = members.get(memberId);
      const zone = member === undefined ? 'UTC' : member.siteZone;
      return mine.filter((entry) => rangeContains(window, civilDateIn(zone, entry.at)));
    },
    byHabit: async (habitId: HabitId) =>
      [...entries.values()].filter((entry) => entry.habitId === habitId),
    add: async (entry: CheckIn) => {
      if (entries.has(entry.id)) {
        throw new ConflictError(`check in ${entry.id} is already stored`);
      }
      entries.set(entry.id, entry);
    },
    replace: async (entry: CheckIn) => {
      if (!entries.has(entry.id)) throw new NotFoundError('check in', entry.id);
      entries.set(entry.id, entry);
    },
  };
}

function overrideRepository(rows: RosterOverride[]): OverrideRepository {
  return {
    byMember: async (memberId: CrewMemberId) => rows.filter((row) => row.memberId === memberId),
    add: async (entry: RosterOverride) => {
      rows.push(entry);
    },
  };
}

function shieldRepository(rows: Shield[]): ShieldRepository {
  return {
    byMember: async (memberId: CrewMemberId) => rows.filter((row) => row.memberId === memberId),
    add: async (entry: Shield) => {
      rows.push(entry);
    },
  };
}

function crewRepository(crews: Map<string, Crew>, rows: Membership[]): CrewRepository {
  return {
    byId: async (id: CrewId) => crews.get(id),
    all: async () => [...crews.values()],
    save: async (entry: Crew) => {
      crews.set(entry.id, entry);
    },
    memberships: async () => [...rows],
    addMembership: async (entry: Membership) => {
      rows.push(entry);
    },
    replaceMembership: async (entry: Membership) => {
      const index = rows.findIndex(
        (row) => row.memberId === entry.memberId && row.crewId === entry.crewId,
      );
      if (index < 0) throw new NotFoundError('membership', entry.memberId);
      rows[index] = entry;
    },
  };
}

function sharingRepository(rows: Map<string, SharingPreference>): SharingRepository {
  return {
    byMember: async (memberId: CrewMemberId) => rows.get(memberId),
    save: async (preference: SharingPreference) => {
      rows.set(preference.memberId, preference);
    },
  };
}

function policyRepository(rows: Map<string, NudgePolicy>): PolicyRepository {
  return {
    byMember: async (memberId: CrewMemberId) => rows.get(memberId),
    save: async (memberId: CrewMemberId, policy: NudgePolicy) => {
      rows.set(memberId, policy);
    },
  };
}

export interface MemoryStoreOptions {
  readonly clock?: Clock;
  readonly ids?: IdSource;
}

export function memoryStore(options: MemoryStoreOptions = {}): MemoryStore {
  const members = new Map<string, CrewMember>();
  const habits = new Map<string, Habit>();
  const checkIns = new Map<string, CheckIn>();
  const overrides: RosterOverride[] = [];
  const shields: Shield[] = [];
  const crews = new Map<string, Crew>();
  const memberships: Membership[] = [];
  const sharing = new Map<string, SharingPreference>();
  const policies = new Map<string, NudgePolicy>();

  return {
    clock: options.clock ?? systemClock(),
    ids: options.ids ?? randomIds(),
    members: memberRepository(members),
    habits: habitRepository(habits),
    checkIns: checkInRepository(checkIns, members),
    overrides: overrideRepository(overrides),
    shields: shieldRepository(shields),
    crews: crewRepository(crews, memberships),
    sharing: sharingRepository(sharing),
    policies: policyRepository(policies),
    reset: () => {
      members.clear();
      habits.clear();
      checkIns.clear();
      overrides.length = 0;
      shields.length = 0;
      crews.clear();
      memberships.length = 0;
      sharing.clear();
      policies.clear();
    },
  };
}
