import { ConflictError, NotFoundError, ValidationError } from '../domain/errors.js';
import {
  amendReading,
  checkIn,
  voidCheckIn,
  type CheckIn,
  CLOCK_SLACK_MS,
  type CheckInSource,
} from '../domain/checkin/checkin.js';
import { resolveAll, type ResolvedCheckIn } from '../domain/checkin/resolve.js';
import { checkInId, type CheckInId, type CrewMemberId, type HabitId } from '../domain/ids.js';
import { isRunningOn } from '../domain/habit/habit.js';
import { civilDateIn, parseInstant, type Instant } from '../domain/time/zone.js';
import { daysBetween } from '../domain/time/civil.js';
import type { DateRange } from '../domain/time/range.js';
import { domainEvent } from './events.js';
import type { Ports } from './ports.js';

export interface RecordCheckInCommand {
  readonly habitId: HabitId;
  readonly at?: string;
  readonly reading?: number;
  readonly note?: string;
  readonly source?: CheckInSource;
  readonly idempotencyKey?: string;
}

export interface RecordResult {
  readonly entry: CheckIn;
  /** False when an idempotency key had already been used and the old row stands. */
  readonly accepted: boolean;
}

export interface CheckInsService {
  record(command: RecordCheckInCommand): Promise<RecordResult>;
  voidEntry(id: CheckInId, at?: string): Promise<CheckIn>;
  amend(id: CheckInId, reading: number | undefined): Promise<CheckIn>;
  listFor(memberId: CrewMemberId, window?: DateRange): Promise<ResolvedCheckIn[]>;
}

/** How far back a check in may be dated. Anything older needs a supervisor. */
export const BACKFILL_LIMIT_DAYS = 14;
export function checkInsService(ports: Ports): CheckInsService {
  async function loadEntry(id: CheckInId): Promise<CheckIn> {
    const found = await ports.checkIns.byId(id);
    if (found === undefined) throw new NotFoundError('check in', id);
    return found;
  }

  return {
    async record(command: RecordCheckInCommand): Promise<RecordResult> {
      const habit = await ports.habits.byId(command.habitId);
      if (habit === undefined) throw new NotFoundError('habit', command.habitId);
      const member = await ports.members.byId(habit.memberId);
      if (member === undefined) throw new NotFoundError('crew member', habit.memberId);

      const now = ports.clock.now();
      const at: Instant = command.at === undefined ? now : parseInstant(command.at);
      if (at.epochMs > now.epochMs + CLOCK_SLACK_MS) {
        throw ValidationError.field('at', 'is in the future');
      }

      const onDate = civilDateIn(member.siteZone, at);
      const age = daysBetween(onDate, civilDateIn(member.siteZone, now));
      const source = command.source ?? 'app';
      if (age > BACKFILL_LIMIT_DAYS && source !== 'supervisor' && source !== 'import') {
        throw ValidationError.field(
          'at',
          `is ${age} days back, past the ${BACKFILL_LIMIT_DAYS} day limit`,
        );
      }
      if (!isRunningOn(habit, onDate)) {
        throw ValidationError.field('at', 'falls on a day the habit was not running');
      }

      if (command.idempotencyKey !== undefined) {
        const seen = (await ports.checkIns.byHabit(habit.id)).find(
          (entry) => entry.idempotencyKey === command.idempotencyKey,
        );
        if (seen !== undefined) return { entry: seen, accepted: false };
      }

      const entry = checkIn({
        id: checkInId(ports.ids.next('CheckInId')),
        habitId: habit.id,
        memberId: member.id,
        at,
        recordedAt: now,
        source,
        reading: command.reading,
        note: command.note,
        idempotencyKey: command.idempotencyKey,
      });
      await ports.checkIns.add(entry);
      await ports.events?.publish(
        domainEvent({
          name: 'checkin.recorded',
          at: now,
          memberId: member.id,
          habitId: habit.id,
          checkInId: entry.id,
          detail: source,
        }),
      );
      return { entry, accepted: true };
    },

    async voidEntry(id: CheckInId, at?: string): Promise<CheckIn> {
      const entry = await loadEntry(id);
      if (entry.voidedAt !== undefined) {
        throw new ConflictError(`check in ${id} is already voided`);
      }
      const voided = voidCheckIn(entry, at === undefined ? ports.clock.now() : parseInstant(at));
      await ports.checkIns.replace(voided);
      await ports.events?.publish(
        domainEvent({
          name: 'checkin.voided',
          at: ports.clock.now(),
          memberId: voided.memberId,
          habitId: voided.habitId,
          checkInId: voided.id,
        }),
      );
      return voided;
    },

    async amend(id: CheckInId, reading: number | undefined): Promise<CheckIn> {
      const amended = amendReading(await loadEntry(id), reading);
      await ports.checkIns.replace(amended);
      return amended;
    },

    async listFor(memberId: CrewMemberId, window?: DateRange): Promise<ResolvedCheckIn[]> {
      const member = await ports.members.byId(memberId);
      if (member === undefined) throw new NotFoundError('crew member', memberId);
      const rows = await ports.checkIns.byMember(memberId, window);
      const live = rows.filter((entry) => entry.voidedAt === undefined);
      return resolveAll(member, live);
    },
  };
}
