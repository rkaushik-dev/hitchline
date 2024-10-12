import { addDays, formatCivilDate, isSameDay, type CivilDate } from '../time/civil.js';
import { civilDateIn, type Instant, type ZoneId } from '../time/zone.js';
import type { CrewMember } from '../crew/member.js';
import { roleOn } from './assignment.js';
import type { DayRole } from './pattern.js';

/**
 * Which clock a day is measured on, and why. A travel day belongs to wherever
 * the person is heading, because the flight lands before the day is over and
 * everything they are asked to do that evening happens at the far end.
 */
export type ZoneReason = 'site' | 'home' | 'outbound' | 'inbound';

export interface ZoneChoice {
  readonly zone: ZoneId;
  readonly reason: ZoneReason;
}

export interface RotationDay {
  readonly date: CivilDate;
  readonly role: DayRole;
  readonly zone: ZoneId;
  readonly reason: ZoneReason;
  /** True when the two zones disagreed about which date it was. */
  readonly straddled: boolean;
}

function travelReason(member: CrewMember, date: CivilDate): ZoneReason {
  const nextRole = roleOn(member.rotation, addDays(date, 1));
  if (nextRole === 'onshift') return 'outbound';
  if (nextRole === 'travel') {
    // A pair of travel days in a row. Look one further on before deciding.
    return roleOn(member.rotation, addDays(date, 2)) === 'onshift' ? 'outbound' : 'inbound';
  }
  return 'inbound';
}

export function zoneOn(member: CrewMember, date: CivilDate): ZoneChoice {
  const role = roleOn(member.rotation, date);
  switch (role) {
    case 'onshift':
      return { zone: member.siteZone, reason: 'site' };
    case 'travel': {
      const reason = travelReason(member, date);
      return { zone: reason === 'outbound' ? member.siteZone : member.homeZone, reason };
    }
    case 'offshift':
    case 'standby':
      return { zone: member.homeZone, reason: 'home' };
  }
}

/**
 * Works out which rotation day an instant belongs to. The site clock is read
 * first because that is where the work is; if the zone that day turns out to
 * be the home one, the reading is taken again there. When the two disagree
 * about the date the day is marked as straddled so a report can say so.
 */
export function rotationDayAt(member: CrewMember, at: Instant): RotationDay {
  const siteDate = civilDateIn(member.siteZone, at);
  const first = zoneOn(member, siteDate);
  const settledDate = civilDateIn(first.zone, at);
  const straddled = !isSameDay(siteDate, settledDate);

  if (!straddled) {
    return {
      date: siteDate,
      role: roleOn(member.rotation, siteDate),
      zone: first.zone,
      reason: first.reason,
      straddled: false,
    };
  }

  const second = zoneOn(member, settledDate);
  if (second.zone === first.zone) {
    return {
      date: settledDate,
      role: roleOn(member.rotation, settledDate),
      zone: second.zone,
      reason: second.reason,
      straddled: true,
    };
  }
  // The two readings pull in opposite directions, which happens on the day a
  // rotation turns over. The site clock wins so the shift keeps its own day.
  return {
    date: siteDate,
    role: roleOn(member.rotation, siteDate),
    zone: member.siteZone,
    reason: 'site',
    straddled: true,
  };
}

export function rotationDayOn(member: CrewMember, date: CivilDate): RotationDay {
  const choice = zoneOn(member, date);
  return {
    date,
    role: roleOn(member.rotation, date),
    zone: choice.zone,
    reason: choice.reason,
    straddled: false,
  };
}

export function describeRotationDay(day: RotationDay): string {
  const straddle = day.straddled ? ' straddled' : '';
  return `${formatCivilDate(day.date)} ${day.role} on ${day.zone} as ${day.reason}${straddle}`;
}
