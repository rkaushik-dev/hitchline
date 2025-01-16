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
 *
 * Nothing here awaits anything, so the methods hand back a settled promise
 * rather than being declared async. That keeps the port asynchronous without
 * pretending there is io going on.
 */
export interface MemoryStore extends Ports {
  reset(): void;
}

function done<T>(value: T): Promise<T> {
  return Promise.resolve(value);
}

function memberRepository(members: Map<string, CrewMember>): MemberRepository {
  return {
    byId: (id: CrewMemberId) => done(members.get(id)),
    all: () => done([...members.values()]),
    save: (member: CrewMember) => {
      members.set(member.id, member);
      return done(undefined);
    },
  };
}

function habitRepository(habits: Map<string, Habit>): HabitRepository {
  return {
    byId: (id: HabitId) => done(habits.get(id)),
    byMember: (memberId: CrewMemberId) =>
      done([...habits.values()].filter((habit) => habit.memberId === memberId)),
    save: (habit: Habit) => {
      habits.set(habit.id, habit);
      return done(undefined);
    },
  };
}

function checkInRepository(
  entries: Map<string, CheckIn>,
  members: Map<string, CrewMember>,
): CheckInRepository {
  return {
    byId: (id: CheckInId) => done(entries.get(id)),
    // A coarse filter on the site clock. A service that needs the rotation day
    // resolves it properly through the zoning rules once the rows are loaded.
    byMember: (memberId: CrewMemberId, window?: DateRange) => {
      const mine = [...entries.values()].filter((entry) => entry.memberId === memberId);
      if (window === undefined) return done(mine);
      const zone = members.get(memberId)?.siteZone ?? 'UTC';
      return done(mine.filter((entry) => rangeContains(window, civilDateIn(zone, entry.at))));
    },
    byHabit: (habitId: HabitId) =>
      done([...entries.values()].filter((entry) => entry.habitId === habitId)),
    add: (entry: CheckIn) => {
      if (entries.has(entry.id)) {
        return Promise.reject(new ConflictError(`check in ${entry.id} is already stored`));
      }
      entries.set(entry.id, entry);
      return done(undefined);
    },
    replace: (entry: CheckIn) => {
      if (!entries.has(entry.id)) {
        return Promise.reject(new NotFoundError('check in', entry.id));
      }
      entries.set(entry.id, entry);
      return done(undefined);
    },
  };
}

function overrideRepository(rows: RosterOverride[]): OverrideRepository {
  return {
    byMember: (memberId: CrewMemberId) => done(rows.filter((row) => row.memberId === memberId)),
    add: (entry: RosterOverride) => {
      rows.push(entry);
      return done(undefined);
    },
  };
}

function shieldRepository(rows: Shield[]): ShieldRepository {
  return {
    byMember: (memberId: CrewMemberId) => done(rows.filter((row) => row.memberId === memberId)),
    add: (entry: Shield) => {
      rows.push(entry);
      return done(undefined);
    },
  };
}

function crewRepository(crews: Map<string, Crew>, rows: Membership[]): CrewRepository {
  return {
    byId: (id: CrewId) => done(crews.get(id)),
    all: () => done([...crews.values()]),
    save: (entry: Crew) => {
      crews.set(entry.id, entry);
      return done(undefined);
    },
    memberships: () => done([...rows]),
    addMembership: (entry: Membership) => {
      rows.push(entry);
      return done(undefined);
    },
    replaceMembership: (entry: Membership) => {
      const index = rows.findIndex(
        (row) => row.memberId === entry.memberId && row.crewId === entry.crewId,
      );
      if (index < 0) return Promise.reject(new NotFoundError('membership', entry.memberId));
      rows[index] = entry;
      return done(undefined);
    },
  };
}

function sharingRepository(rows: Map<string, SharingPreference>): SharingRepository {
  return {
    byMember: (memberId: CrewMemberId) => done(rows.get(memberId)),
    save: (preference: SharingPreference) => {
      rows.set(preference.memberId, preference);
      return done(undefined);
    },
  };
}

function policyRepository(rows: Map<string, NudgePolicy>): PolicyRepository {
  return {
    byMember: (memberId: CrewMemberId) => done(rows.get(memberId)),
    save: (memberId: CrewMemberId, policy: NudgePolicy) => {
      rows.set(memberId, policy);
      return done(undefined);
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
