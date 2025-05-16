import { NotFoundError, ValidationError } from '../domain/errors.js';
import { parseCadence } from '../domain/habit/cadence.js';
import {
  habit,
  moveHabitTo,
  recadence,
  renameHabit,
  retag,
  retarget,
  type Habit,
  type HabitStatus,
} from '../domain/habit/habit.js';
import { parseTarget } from '../domain/habit/target.js';
import { habitId, type CrewMemberId, type HabitId } from '../domain/ids.js';
import { isActiveMember } from '../domain/crew/member.js';
import { civilDateIn, type Instant } from '../domain/time/zone.js';
import { parseCivilDate, type CivilDate } from '../domain/time/civil.js';
import { domainEvent, type EventName } from './events.js';
import type { Ports } from './ports.js';

export interface CreateHabitCommand {
  readonly memberId: CrewMemberId;
  readonly name: string;
  readonly cadence: string;
  readonly target?: string;
  readonly startsOn?: string;
  readonly endsOn?: string;
  readonly tags?: readonly string[];
  readonly graceDays?: number;
}

export interface HabitsService {
  create(command: CreateHabitCommand): Promise<Habit>;
  get(id: HabitId): Promise<Habit>;
  listFor(memberId: CrewMemberId): Promise<Habit[]>;
  rename(id: HabitId, name: string): Promise<Habit>;
  moveTo(id: HabitId, status: HabitStatus): Promise<Habit>;
  changeCadence(id: HabitId, cadence: string): Promise<Habit>;
  changeTarget(id: HabitId, target: string | undefined): Promise<Habit>;
  changeTags(id: HabitId, tags: readonly string[]): Promise<Habit>;
}

/**
 * The habit use cases. Everything that changes a habit goes through here so
 * there is one place that checks the member is still on the books and one
 * place that reads the clock.
 */
export function habitsService(ports: Ports): HabitsService {
  async function load(id: HabitId): Promise<Habit> {
    const found = await ports.habits.byId(id);
    if (found === undefined) throw new NotFoundError('habit', id);
    return found;
  }

  async function saved(entry: Habit, name: EventName, detail: string): Promise<Habit> {
    await ports.habits.save(entry);
    await ports.events?.publish(
      domainEvent({
        name,
        at: ports.clock.now(),
        memberId: entry.memberId,
        habitId: entry.id,
        detail,
      }),
    );
    return entry;
  }

  function today(at: Instant, zone: string): CivilDate {
    return civilDateIn(zone, at);
  }

  return {
    async create(command: CreateHabitCommand): Promise<Habit> {
      const member = await ports.members.byId(command.memberId);
      if (member === undefined) throw new NotFoundError('crew member', command.memberId);
      if (!isActiveMember(member)) {
        throw ValidationError.field('memberId', 'is not an active crew member');
      }
      const now = ports.clock.now();
      const entry = habit({
        id: habitId(ports.ids.next('HabitId')),
        memberId: member.id,
        name: command.name,
        cadence: parseCadence(command.cadence),
        target: command.target === undefined ? undefined : parseTarget(command.target),
        startsOn:
          command.startsOn === undefined
            ? today(now, member.homeZone)
            : parseCivilDate(command.startsOn),
        endsOn: command.endsOn === undefined ? undefined : parseCivilDate(command.endsOn),
        tags: command.tags ?? [],
        graceDays: command.graceDays ?? 0,
        createdAt: now,
      });
      return saved(entry, 'habit.created', entry.name);
    },

    get: load,

    async listFor(memberId: CrewMemberId): Promise<Habit[]> {
      const found = await ports.habits.byMember(memberId);
      return found.sort((left, right) => left.name.localeCompare(right.name));
    },

    async rename(id: HabitId, name: string): Promise<Habit> {
      return saved(renameHabit(await load(id), name), 'habit.changed', `renamed to ${name}`);
    },

    async moveTo(id: HabitId, status: HabitStatus): Promise<Habit> {
      const moved = moveHabitTo(await load(id), status);
      const event: EventName = status === 'archived' ? 'habit.archived' : 'habit.changed';
      return saved(moved, event, `status ${status}`);
    },

    async changeCadence(id: HabitId, cadence: string): Promise<Habit> {
      const changed = recadence(await load(id), parseCadence(cadence));
      return saved(changed, 'habit.changed', `cadence ${cadence}`);
    },

    async changeTarget(id: HabitId, target: string | undefined): Promise<Habit> {
      const parsed = target === undefined ? undefined : parseTarget(target);
      return saved(
        retarget(await load(id), parsed),
        'habit.changed',
        `target ${target ?? 'cleared'}`,
      );
    },

    async changeTags(id: HabitId, tags: readonly string[]): Promise<Habit> {
      return saved(retag(await load(id), tags), 'habit.changed', `tags ${tags.join(' ')}`);
    },
  };
}
