import { ValidationError } from '../domain/errors.js';
import type { CheckInId, CrewId, CrewMemberId, HabitId } from '../domain/ids.js';
import { formatInstant, type Instant } from '../domain/time/zone.js';

/**
 * What happened, said once. Services publish these after the write lands, and
 * anything that wants to react reads them here rather than being called from
 * inside the service that did the work.
 */
export type EventName =
  | 'habit.created'
  | 'habit.changed'
  | 'habit.archived'
  | 'checkin.recorded'
  | 'checkin.voided'
  | 'roster.held'
  | 'shield.granted'
  | 'crew.enrolled';

export interface DomainEvent {
  readonly name: EventName;
  readonly at: Instant;
  readonly memberId: CrewMemberId;
  readonly habitId: HabitId | undefined;
  readonly checkInId: CheckInId | undefined;
  readonly crewId: CrewId | undefined;
  readonly detail: string;
}

export interface EventInput {
  readonly name: EventName;
  readonly at: Instant;
  readonly memberId: CrewMemberId;
  readonly habitId?: HabitId;
  readonly checkInId?: CheckInId;
  readonly crewId?: CrewId;
  readonly detail?: string;
}

const DETAIL_MAX = 240;

export function domainEvent(input: EventInput): DomainEvent {
  const detail = (input.detail ?? '').trim();
  if (detail.length > DETAIL_MAX) {
    throw ValidationError.field('detail', `must be at most ${DETAIL_MAX} characters`);
  }
  return {
    name: input.name,
    at: input.at,
    memberId: input.memberId,
    habitId: input.habitId,
    checkInId: input.checkInId,
    crewId: input.crewId,
    detail,
  };
}

export type Subscriber = (event: DomainEvent) => void | Promise<void>;

export interface EventBus {
  on(name: EventName | '*', subscriber: Subscriber): () => void;
  publish(event: DomainEvent): Promise<void>;
  readonly listeners: number;
}

/**
 * An in process bus. A subscriber that throws is reported and the rest still
 * run: an audit line failing to write must not undo a check in that already
 * landed in the journal.
 */
export function createBus(onError?: (error: unknown, event: DomainEvent) => void): EventBus {
  const byName = new Map<string, Set<Subscriber>>();

  return {
    on(name: EventName | '*', subscriber: Subscriber): () => void {
      const held = byName.get(name) ?? new Set<Subscriber>();
      held.add(subscriber);
      byName.set(name, held);
      return () => {
        held.delete(subscriber);
      };
    },

    async publish(event: DomainEvent): Promise<void> {
      const wanted = [...(byName.get(event.name) ?? []), ...(byName.get('*') ?? [])];
      for (const subscriber of wanted) {
        try {
          await subscriber(event);
        } catch (error) {
          onError?.(error, event);
        }
      }
    },

    get listeners(): number {
      let total = 0;
      for (const held of byName.values()) total += held.size;
      return total;
    },
  };
}

export function describeEvent(event: DomainEvent): string {
  const subject = event.habitId ?? event.checkInId ?? event.crewId ?? event.memberId;
  const detail = event.detail.length === 0 ? '' : ` ${event.detail}`;
  return `${formatInstant(event.at)} ${event.name} ${subject}${detail}`;
}
