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
import { civilDateIn, formatInstant } from '../../domain/time/zone.js';
import type { Clock, Durable, IdSource, Ports } from '../../app/ports.js';
import { systemClock } from '../clock.js';
import { randomIds } from '../id.js';
import {
  decodeCheckIn,
  decodeCrew,
  decodeHabit,
  decodeMember,
  decodeMembership,
  decodeOverride,
  decodePolicy,
  decodeSharing,
  decodeShield,
  encodeCheckIn,
  encodeCrew,
  encodeHabit,
  encodeMember,
  encodeMembership,
  encodeOverride,
  encodePolicy,
  encodeSharing,
  encodeShield,
  type Row,
} from './codec.js';
import { collect, fold, openJournal, type Journal } from './journal.js';

/**
 * The store the service actually runs on. State is held in memory for reads
 * and every change is appended to the journal before it is acknowledged, so a
 * crash loses nothing that was reported as saved.
 */
export interface FileStore extends Ports, Durable {
  reload(): Promise<void>;
  readonly path: string;
}

export interface FileStoreOptions {
  readonly path: string;
  readonly clock?: Clock;
  readonly ids?: IdSource;
}

const idOf = (row: Row): string => String(row['id']);
const memberKey = (row: Row): string => String(row['memberId']);
const membershipKey = (row: Row): string => `${String(row['crewId'])}|${String(row['memberId'])}`;

export async function fileStore(options: FileStoreOptions): Promise<FileStore> {
  const journal: Journal = await openJournal(options.path);
  const clock = options.clock ?? systemClock();

  const members = new Map<string, CrewMember>();
  const habits = new Map<string, Habit>();
  const checkIns = new Map<string, CheckIn>();
  const crews = new Map<string, Crew>();
  const memberships = new Map<string, Membership>();
  const sharing = new Map<string, SharingPreference>();
  const policies = new Map<string, NudgePolicy>();
  let overrides: RosterOverride[] = [];
  let shields: Shield[] = [];

  async function reload(): Promise<void> {
    const report = await journal.read();
    members.clear();
    habits.clear();
    checkIns.clear();
    crews.clear();
    memberships.clear();
    sharing.clear();
    policies.clear();

    for (const row of fold(report.entries, 'member', idOf).values()) {
      const member = decodeMember(row);
      members.set(member.id, member);
    }
    for (const row of fold(report.entries, 'habit', idOf).values()) {
      const entry = decodeHabit(row);
      habits.set(entry.id, entry);
    }
    for (const row of fold(report.entries, 'checkin', idOf).values()) {
      const entry = decodeCheckIn(row);
      checkIns.set(entry.id, entry);
    }
    for (const row of fold(report.entries, 'crew', idOf).values()) {
      const entry = decodeCrew(row);
      crews.set(entry.id, entry);
    }
    const storedMemberships = fold(report.entries, 'membership', membershipKey);
    for (const key of storedMemberships.keys()) {
      const row = storedMemberships.get(key);
      if (row === undefined) continue;
      memberships.set(key, decodeMembership(row));
    }
    for (const row of fold(report.entries, 'sharing', memberKey).values()) {
      const preference = decodeSharing(row);
      sharing.set(preference.memberId, preference);
    }
    for (const row of fold(report.entries, 'policy', memberKey).values()) {
      const { memberId, policy } = decodePolicy(row);
      policies.set(memberId, policy);
    }
    overrides = collect(report.entries, 'override').map(decodeOverride);
    shields = collect(report.entries, 'shield').map(decodeShield);
  }

  await reload();

  const now = (): string => formatInstant(clock.now());

  return {
    path: options.path,
    clock,
    ids: options.ids ?? randomIds(),

    members: {
      byId: (id: CrewMemberId) => Promise.resolve(members.get(id)),
      all: () => Promise.resolve([...members.values()]),
      async save(member: CrewMember) {
        await journal.append('member', 'put', encodeMember(member), now());
        members.set(member.id, member);
      },
    },

    habits: {
      byId: (id: HabitId) => Promise.resolve(habits.get(id)),
      byMember: (memberId: CrewMemberId) =>
        Promise.resolve([...habits.values()].filter((entry) => entry.memberId === memberId)),
      async save(entry: Habit) {
        await journal.append('habit', 'put', encodeHabit(entry), now());
        habits.set(entry.id, entry);
      },
    },

    checkIns: {
      byId: (id: CheckInId) => Promise.resolve(checkIns.get(id)),
      byMember(memberId: CrewMemberId, window?: DateRange) {
        const mine = [...checkIns.values()].filter((entry) => entry.memberId === memberId);
        if (window === undefined) return Promise.resolve(mine);
        const zone = members.get(memberId)?.siteZone ?? 'UTC';
        return Promise.resolve(
          mine.filter((entry) => rangeContains(window, civilDateIn(zone, entry.at))),
        );
      },
      byHabit: (habitId: HabitId) =>
        Promise.resolve([...checkIns.values()].filter((entry) => entry.habitId === habitId)),
      async add(entry: CheckIn) {
        if (checkIns.has(entry.id)) {
          throw new ConflictError(`check in ${entry.id} is already stored`);
        }
        await journal.append('checkin', 'put', encodeCheckIn(entry), now());
        checkIns.set(entry.id, entry);
      },
      async replace(entry: CheckIn) {
        if (!checkIns.has(entry.id)) throw new NotFoundError('check in', entry.id);
        await journal.append('checkin', 'put', encodeCheckIn(entry), now());
        checkIns.set(entry.id, entry);
      },
    },

    overrides: {
      byMember: (memberId: CrewMemberId) =>
        Promise.resolve(overrides.filter((entry) => entry.memberId === memberId)),
      async add(entry: RosterOverride) {
        await journal.append('override', 'put', encodeOverride(entry), now());
        overrides.push(entry);
      },
    },

    shields: {
      byMember: (memberId: CrewMemberId) =>
        Promise.resolve(shields.filter((entry) => entry.memberId === memberId)),
      async add(entry: Shield) {
        await journal.append('shield', 'put', encodeShield(entry), now());
        shields.push(entry);
      },
    },

    crews: {
      byId: (id: CrewId) => Promise.resolve(crews.get(id)),
      all: () => Promise.resolve([...crews.values()]),
      async save(entry: Crew) {
        await journal.append('crew', 'put', encodeCrew(entry), now());
        crews.set(entry.id, entry);
      },
      memberships: () => Promise.resolve([...memberships.values()]),
      async addMembership(entry: Membership) {
        await journal.append('membership', 'put', encodeMembership(entry), now());
        memberships.set(`${entry.crewId}|${entry.memberId}`, entry);
      },
      async replaceMembership(entry: Membership) {
        const key = `${entry.crewId}|${entry.memberId}`;
        if (!memberships.has(key)) throw new NotFoundError('membership', entry.memberId);
        await journal.append('membership', 'put', encodeMembership(entry), now());
        memberships.set(key, entry);
      },
    },

    sharing: {
      byMember: (memberId: CrewMemberId) => Promise.resolve(sharing.get(memberId)),
      async save(preference: SharingPreference) {
        await journal.append('sharing', 'put', encodeSharing(preference), now());
        sharing.set(preference.memberId, preference);
      },
    },

    policies: {
      byMember: (memberId: CrewMemberId) => Promise.resolve(policies.get(memberId)),
      async save(memberId: CrewMemberId, policy: NudgePolicy) {
        await journal.append('policy', 'put', encodePolicy(memberId, policy), now());
        policies.set(memberId, policy);
      },
    },

    reload,
    flush: () => journal.flush(),
    close: () => journal.close(),
  };
}
