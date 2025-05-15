import type { CrewMemberId } from '../domain/ids.js';
import { rangeContains, type DateRange } from '../domain/time/range.js';
import { civilDateIn, type ZoneId } from '../domain/time/zone.js';
import type { DomainEvent, EventBus, EventName } from './events.js';

/**
 * The audit trail. An operator being asked why an adherence figure moved needs
 * to see what was done and when, and the check in ledger only holds half of
 * that: it says nothing about a roster held over or a habit paused.
 */
export interface AuditEntry extends DomainEvent {
  readonly sequence: number;
}

export interface AuditLog {
  record(event: DomainEvent): AuditEntry;
  all(): readonly AuditEntry[];
  forMember(memberId: CrewMemberId): AuditEntry[];
  inWindow(window: DateRange, zone: ZoneId): AuditEntry[];
  named(name: EventName): AuditEntry[];
  readonly size: number;
}

export interface AuditOptions {
  /** Oldest entries are dropped once the log passes this many. */
  readonly keep?: number;
}

const DEFAULT_KEEP = 10_000;

export function createAuditLog(options: AuditOptions = {}): AuditLog {
  const keep = options.keep ?? DEFAULT_KEEP;
  const entries: AuditEntry[] = [];
  let sequence = 0;

  return {
    record(event: DomainEvent): AuditEntry {
      sequence += 1;
      const entry: AuditEntry = { ...event, sequence };
      entries.push(entry);
      if (entries.length > keep) entries.splice(0, entries.length - keep);
      return entry;
    },

    all: () => entries,

    forMember: (memberId: CrewMemberId) => entries.filter((entry) => entry.memberId === memberId),

    inWindow: (window: DateRange, zone: ZoneId) =>
      entries.filter((entry) => rangeContains(window, civilDateIn(zone, entry.at))),

    named: (name: EventName) => entries.filter((entry) => entry.name === name),

    get size(): number {
      return entries.length;
    },
  };
}

/** Wires a log onto a bus. Returns the unsubscribe, which the tests use. */
export function auditEverything(bus: EventBus, log: AuditLog): () => void {
  return bus.on('*', (event) => {
    log.record(event);
  });
}
