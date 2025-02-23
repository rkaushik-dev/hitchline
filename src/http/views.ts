import { formatCadence } from '../domain/habit/cadence.js';
import { formatTarget } from '../domain/habit/target.js';
import type { Habit } from '../domain/habit/habit.js';
import type { CheckIn } from '../domain/checkin/checkin.js';
import type { ResolvedCheckIn } from '../domain/checkin/resolve.js';
import type { Crew, Membership } from '../domain/crew/crew.js';
import type { CrewMember } from '../domain/crew/member.js';
import type { RosterOverride } from '../domain/rotation/override.js';
import type { Shield } from '../domain/adherence/shield.js';
import type { ObligationResult } from '../domain/adherence/match.js';
import type { PeriodSummary } from '../domain/adherence/summary.js';
import type { StreakSummary } from '../domain/adherence/streak.js';
import type { PlannedNudge } from '../domain/nudge/planner.js';
import { formatRotationPattern } from '../domain/rotation/pattern.js';
import { formatCivilDate } from '../domain/time/civil.js';
import { formatDateRange } from '../domain/time/range.js';
import { formatInstant } from '../domain/time/zone.js';

/**
 * Wire shapes. Nothing in `src/domain` knows about json, so every response
 * body is built here and the domain types stay free of presentation.
 */
export interface HabitView {
  readonly id: string;
  readonly memberId: string;
  readonly name: string;
  readonly cadence: string;
  readonly target: string | null;
  readonly status: string;
  readonly startsOn: string;
  readonly endsOn: string | null;
  readonly tags: readonly string[];
  readonly graceDays: number;
}

export function habitView(entry: Habit): HabitView {
  return {
    id: entry.id,
    memberId: entry.memberId,
    name: entry.name,
    cadence: formatCadence(entry.cadence),
    target: entry.target === undefined ? null : formatTarget(entry.target),
    status: entry.status,
    startsOn: formatCivilDate(entry.startsOn),
    endsOn: entry.endsOn === undefined ? null : formatCivilDate(entry.endsOn),
    tags: entry.tags,
    graceDays: entry.graceDays,
  };
}

export interface MemberView {
  readonly id: string;
  readonly displayName: string;
  readonly homeZone: string;
  readonly siteZone: string;
  readonly pattern: string;
  readonly anchor: string;
  readonly phase: number;
  readonly status: string;
}

export function memberView(member: CrewMember): MemberView {
  return {
    id: member.id,
    displayName: member.displayName,
    homeZone: member.homeZone,
    siteZone: member.siteZone,
    pattern: formatRotationPattern(member.rotation.pattern),
    anchor: formatCivilDate(member.rotation.anchor),
    phase: member.rotation.phase,
    status: member.status,
  };
}

export interface CheckInView {
  readonly id: string;
  readonly habitId: string;
  readonly at: string;
  readonly reading: number | null;
  readonly note: string;
  readonly source: string;
  readonly onDate: string | null;
  readonly zone: string | null;
}

export function checkInView(entry: CheckIn): CheckInView {
  return {
    id: entry.id,
    habitId: entry.habitId,
    at: formatInstant(entry.at),
    reading: entry.reading ?? null,
    note: entry.note,
    source: entry.source,
    onDate: null,
    zone: null,
  };
}

export function resolvedView(item: ResolvedCheckIn): CheckInView {
  return {
    ...checkInView(item.checkIn),
    onDate: formatCivilDate(item.day.date),
    zone: item.day.zone,
  };
}

export interface ResultView {
  readonly habitId: string;
  readonly scope: string;
  readonly window: string;
  readonly dueOn: string;
  readonly times: number;
  readonly count: number;
  readonly total: number | null;
  readonly status: string;
  readonly progress: number;
}

export function resultView(result: ObligationResult): ResultView {
  return {
    habitId: result.obligation.habitId,
    scope: result.obligation.scope,
    window: formatDateRange(result.obligation.window),
    dueOn: formatCivilDate(result.obligation.dueOn),
    times: result.obligation.times,
    count: result.count,
    total: result.total ?? null,
    status: result.status,
    progress: Math.round(result.progress * 100) / 100,
  };
}

export interface SummaryView {
  readonly window: string;
  readonly owed: number;
  readonly met: number;
  readonly partial: number;
  readonly missed: number;
  readonly pending: number;
  readonly shieldedDays: number;
  readonly rate: number;
}

export function summaryView(summary: PeriodSummary): SummaryView {
  return {
    window: formatDateRange(summary.range),
    owed: summary.owed,
    met: summary.met,
    partial: summary.partial,
    missed: summary.missed,
    pending: summary.pending,
    shieldedDays: summary.shieldedDays,
    rate: Math.round(summary.rate * 100) / 100,
  };
}

export function streakView(summary: StreakSummary): Record<string, unknown> {
  return {
    current: summary.current,
    longest: summary.longest,
    shielded: summary.shielded,
    lastMetOn: summary.lastMetOn === undefined ? null : formatCivilDate(summary.lastMetOn),
    brokenOn: summary.brokenOn === undefined ? null : formatCivilDate(summary.brokenOn),
  };
}

export function overrideView(entry: RosterOverride): Record<string, unknown> {
  return {
    memberId: entry.memberId,
    range: formatDateRange(entry.range),
    role: entry.role,
    reason: entry.reason,
    note: entry.note,
    enteredAt: formatInstant(entry.enteredAt),
  };
}

export function shieldView(entry: Shield): Record<string, unknown> {
  return {
    memberId: entry.memberId,
    range: formatDateRange(entry.range),
    reason: entry.reason,
    note: entry.note,
    grantedBy: entry.grantedBy ?? null,
    grantedAt: formatInstant(entry.grantedAt),
  };
}

export function crewView(entry: Crew): Record<string, unknown> {
  return { id: entry.id, name: entry.name, site: entry.site, siteZone: entry.siteZone };
}

export function membershipView(entry: Membership): Record<string, unknown> {
  return {
    crewId: entry.crewId,
    memberId: entry.memberId,
    role: entry.role,
    from: formatCivilDate(entry.from),
    to: entry.to === undefined ? null : formatCivilDate(entry.to),
  };
}

export function nudgeView(nudge: PlannedNudge): Record<string, unknown> {
  return {
    habitId: nudge.habitId,
    key: nudge.key,
    at: formatInstant(nudge.at),
    onDate: formatCivilDate(nudge.onDate),
    reason: nudge.reason,
  };
}
