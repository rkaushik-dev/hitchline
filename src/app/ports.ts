import type { CheckIn } from '../domain/checkin/checkin.js';
import type { Crew, Membership } from '../domain/crew/crew.js';
import type { CrewMember } from '../domain/crew/member.js';
import type { SharingPreference } from '../domain/crew/sharing.js';
import type { Habit } from '../domain/habit/habit.js';
import type { CheckInId, CrewId, CrewMemberId, HabitId, IdKind } from '../domain/ids.js';
import type { NudgePolicy } from '../domain/nudge/policy.js';
import type { RosterOverride } from '../domain/rotation/override.js';
import type { Shield } from '../domain/adherence/shield.js';
import type { DateRange } from '../domain/time/range.js';
import type { Instant } from '../domain/time/zone.js';
import type { EventBus } from './events.js';

/**
 * Everything the application layer needs from the outside world. The domain
 * knows none of this; the services take these in so a test can hand them a
 * fixed clock and a map, and production can hand them a journal on disk.
 */
export interface Clock {
  now(): Instant;
}

export interface IdSource {
  next(kind: IdKind): string;
}

export interface MemberRepository {
  byId(id: CrewMemberId): Promise<CrewMember | undefined>;
  all(): Promise<CrewMember[]>;
  save(member: CrewMember): Promise<void>;
}

export interface HabitRepository {
  byId(id: HabitId): Promise<Habit | undefined>;
  byMember(memberId: CrewMemberId): Promise<Habit[]>;
  save(habit: Habit): Promise<void>;
}

export interface CheckInRepository {
  byId(id: CheckInId): Promise<CheckIn | undefined>;
  byMember(memberId: CrewMemberId, window?: DateRange): Promise<CheckIn[]>;
  byHabit(habitId: HabitId): Promise<CheckIn[]>;
  add(entry: CheckIn): Promise<void>;
  replace(entry: CheckIn): Promise<void>;
}

export interface OverrideRepository {
  byMember(memberId: CrewMemberId): Promise<RosterOverride[]>;
  add(entry: RosterOverride): Promise<void>;
}

export interface ShieldRepository {
  byMember(memberId: CrewMemberId): Promise<Shield[]>;
  add(entry: Shield): Promise<void>;
}

export interface CrewRepository {
  byId(id: CrewId): Promise<Crew | undefined>;
  all(): Promise<Crew[]>;
  save(crew: Crew): Promise<void>;
  memberships(): Promise<Membership[]>;
  addMembership(entry: Membership): Promise<void>;
  replaceMembership(entry: Membership): Promise<void>;
}

export interface SharingRepository {
  byMember(memberId: CrewMemberId): Promise<SharingPreference | undefined>;
  save(preference: SharingPreference): Promise<void>;
}

export interface PolicyRepository {
  byMember(memberId: CrewMemberId): Promise<NudgePolicy | undefined>;
  save(memberId: CrewMemberId, policy: NudgePolicy): Promise<void>;
}

/** The whole set, passed around as one so a service takes a single argument. */
export interface Ports {
  /** Where services announce what they did. Optional so a test can leave it out. */
  readonly events?: EventBus;
  readonly clock: Clock;
  readonly ids: IdSource;
  readonly members: MemberRepository;
  readonly habits: HabitRepository;
  readonly checkIns: CheckInRepository;
  readonly overrides: OverrideRepository;
  readonly shields: ShieldRepository;
  readonly crews: CrewRepository;
  readonly sharing: SharingRepository;
  readonly policies: PolicyRepository;
}

/** A store that can be flushed, which the journal backed one needs and memory does not. */
export interface Durable {
  flush(): Promise<void>;
  close(): Promise<void>;
}

export function isDurable(value: unknown): value is Durable {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Partial<Durable>;
  return typeof candidate.flush === 'function' && typeof candidate.close === 'function';
}
