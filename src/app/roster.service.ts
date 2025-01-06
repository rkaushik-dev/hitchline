import { NotFoundError } from '../domain/errors.js';
import type { CrewMemberId } from '../domain/ids.js';
import { reassign, type CrewMember } from '../domain/crew/member.js';
import { rotationAssignment, hitchRangeOn } from '../domain/rotation/assignment.js';
import { parseRotationPattern } from '../domain/rotation/pattern.js';
import {
  checkNoClashes,
  rosterOverride,
  type OverrideReason,
  type RosterOverride,
} from '../domain/rotation/override.js';
import { effectiveRoleOn, roster, type Roster } from '../domain/rotation/roster.js';
import { zoneOn } from '../domain/rotation/zoning.js';
import { shield, type Shield, type ShieldReason } from '../domain/adherence/shield.js';
import { formatCivilDate, parseCivilDate, type CivilDate } from '../domain/time/civil.js';
import { daysOf, parseDateRange, type DateRange } from '../domain/time/range.js';
import type { DayRole } from '../domain/rotation/pattern.js';
import type { ZoneId } from '../domain/time/zone.js';
import type { Ports } from './ports.js';

export interface RosterDay {
  readonly date: CivilDate;
  readonly role: DayRole;
  readonly source: 'pattern' | 'override';
  readonly zone: ZoneId;
}

export interface HoldCommand {
  readonly memberId: CrewMemberId;
  readonly range: string;
  readonly role: DayRole;
  readonly reason: OverrideReason;
  readonly note?: string;
}

export interface ShieldCommand {
  readonly memberId: CrewMemberId;
  readonly range: string;
  readonly reason: ShieldReason;
  readonly note?: string;
  readonly grantedBy?: CrewMemberId;
}

export interface ReassignCommand {
  readonly memberId: CrewMemberId;
  readonly pattern: string;
  readonly anchor: string;
  readonly phase?: number;
}

export interface RosterService {
  rosterFor(memberId: CrewMemberId): Promise<Roster>;
  daysFor(memberId: CrewMemberId, window: string): Promise<RosterDay[]>;
  hitchOn(memberId: CrewMemberId, date: string): Promise<DateRange>;
  hold(command: HoldCommand): Promise<RosterOverride>;
  shieldDays(command: ShieldCommand): Promise<Shield>;
  reassignRotation(command: ReassignCommand): Promise<CrewMember>;
}

/**
 * Everything about somebody's calendar. The service exists mainly so that the
 * roster is always assembled the same way: the member, plus every override
 * stored for them, and never one without the other.
 */
export function rosterService(ports: Ports): RosterService {
  async function loadMember(memberId: CrewMemberId): Promise<CrewMember> {
    const found = await ports.members.byId(memberId);
    if (found === undefined) throw new NotFoundError('crew member', memberId);
    return found;
  }

  async function loadRoster(memberId: CrewMemberId): Promise<Roster> {
    const member = await loadMember(memberId);
    return roster(member, await ports.overrides.byMember(memberId));
  }

  return {
    rosterFor: loadRoster,

    async daysFor(memberId: CrewMemberId, window: string): Promise<RosterDay[]> {
      const target = await loadRoster(memberId);
      return daysOf(parseDateRange(window)).map((date) => {
        const effective = effectiveRoleOn(target, date);
        return {
          date,
          role: effective.role,
          source: effective.source,
          zone: zoneOn(target.member, date).zone,
        };
      });
    },

    async hitchOn(memberId: CrewMemberId, date: string): Promise<DateRange> {
      const member = await loadMember(memberId);
      return hitchRangeOn(member.rotation, parseCivilDate(date));
    },

    async hold(command: HoldCommand): Promise<RosterOverride> {
      const member = await loadMember(command.memberId);
      const entry = rosterOverride({
        memberId: member.id,
        range: parseDateRange(command.range),
        role: command.role,
        reason: command.reason,
        note: command.note,
        enteredAt: ports.clock.now(),
      });
      const existing = await ports.overrides.byMember(member.id);
      checkNoClashes([...existing, entry]);
      await ports.overrides.add(entry);
      return entry;
    },

    async shieldDays(command: ShieldCommand): Promise<Shield> {
      const member = await loadMember(command.memberId);
      const entry = shield({
        memberId: member.id,
        range: parseDateRange(command.range),
        reason: command.reason,
        note: command.note,
        grantedBy: command.grantedBy,
        grantedAt: ports.clock.now(),
      });
      await ports.shields.add(entry);
      return entry;
    },

    async reassignRotation(command: ReassignCommand): Promise<CrewMember> {
      const member = await loadMember(command.memberId);
      const assignment = rotationAssignment({
        pattern: parseRotationPattern(command.pattern),
        anchor: parseCivilDate(command.anchor),
        phase: command.phase ?? 0,
      });
      const updated = reassign(member, assignment);
      await ports.members.save(updated);
      return updated;
    },
  };
}

export function describeRosterDay(day: RosterDay): string {
  return `${formatCivilDate(day.date)} ${day.role} (${day.source})`;
}
