import type { HabitId } from '../ids.js';
import { roleFor, type Roster } from '../rotation/roster.js';
import { zoneOn } from '../rotation/zoning.js';
import { formatCivilDate, type CivilDate } from '../time/civil.js';
import { compareClockTime, fromMinutesSinceMidnight, MINUTES_IN_DAY } from '../time/clocktime.js';
import { formatInstant, zonedInstant, type Instant } from '../time/zone.js';
import { obligationKey, obligationSpan } from '../schedule/obligation.js';
import type { ObligationResult } from '../adherence/match.js';
import { firstAllowedTime, isSilent, nudgesOnRole, type NudgePolicy } from './policy.js';

/**
 * Why somebody is being pinged. The reason is carried through to the message,
 * because a person who has already done the thing twice today should not read
 * the same words as one who has not started.
 */
export type NudgeReason = 'nothing_yet' | 'part_way' | 'last_chance';

export interface PlannedNudge {
  readonly habitId: HabitId;
  readonly key: string;
  readonly at: Instant;
  readonly onDate: CivilDate;
  readonly reason: NudgeReason;
}

export interface NudgePlanRequest {
  readonly roster: Roster;
  readonly policy: NudgePolicy;
  readonly results: readonly ObligationResult[];
  readonly asOf: Instant;
}

/** The wall clock minute a nudge wants to land on, read back from midnight. */
function targetMinute(policy: NudgePolicy): number {
  return Math.max(0, Math.min(MINUTES_IN_DAY - 1, MINUTES_IN_DAY - policy.leadMinutes));
}

function reasonFor(result: ObligationResult): NudgeReason {
  if (obligationSpan(result.obligation) > 1) return 'last_chance';
  return result.count === 0 ? 'nothing_yet' : 'part_way';
}

function plannedFor(request: NudgePlanRequest, result: ObligationResult): PlannedNudge | undefined {
  const date = result.obligation.window.to;
  if (!nudgesOnRole(request.policy, roleFor(request.roster, date))) return undefined;

  const wanted = fromMinutesSinceMidnight(targetMinute(request.policy));
  const allowed = firstAllowedTime(request.policy, wanted);
  // Quiet hours that run past midnight would push the nudge into the day after
  // the window closes, which is too late to be worth sending.
  if (compareClockTime(allowed, wanted) < 0) return undefined;

  const at = zonedInstant(zoneOn(request.roster.member, date).zone, date, allowed);
  if (at.epochMs < request.asOf.epochMs) return undefined;

  return {
    habitId: result.obligation.habitId,
    key: obligationKey(result.obligation),
    at,
    onDate: date,
    reason: reasonFor(result),
  };
}

export function planNudges(request: NudgePlanRequest): PlannedNudge[] {
  if (isSilent(request.policy)) return [];
  const planned: PlannedNudge[] = [];
  for (const result of request.results) {
    if (result.status !== 'pending') continue;
    if (result.count >= result.obligation.times) continue;
    const nudge = plannedFor(request, result);
    if (nudge !== undefined) planned.push(nudge);
  }
  planned.sort((left, right) => left.at.epochMs - right.at.epochMs);
  return capPerDay(planned, request.policy);
}

/**
 * Keeps the earliest few on any one day and drops anything too close behind.
 * Nudges that fall on the same minute ride along with the one before them,
 * because two habits due at the same time are one message on the handset.
 */
export function capPerDay(planned: readonly PlannedNudge[], policy: NudgePolicy): PlannedNudge[] {
  const heads = new Map<string, PlannedNudge[]>();
  const kept: PlannedNudge[] = [];
  for (const nudge of planned) {
    const key = formatCivilDate(nudge.onDate);
    const sent = heads.get(key) ?? [];
    const last = sent[sent.length - 1];
    if (last !== undefined && last.at.epochMs === nudge.at.epochMs) {
      kept.push(nudge);
      continue;
    }
    if (sent.length >= policy.maxPerDay) continue;
    if (last !== undefined && nudge.at.epochMs - last.at.epochMs < policy.minGapMinutes * 60_000) {
      continue;
    }
    sent.push(nudge);
    heads.set(key, sent);
    kept.push(nudge);
  }
  return kept;
}

export function describeNudge(nudge: PlannedNudge): string {
  return `${nudge.reason} at ${formatInstant(nudge.at)}`;
}

export function nudgesForHabit(planned: readonly PlannedNudge[], habitId: HabitId): PlannedNudge[] {
  return planned.filter((nudge) => nudge.habitId === habitId);
}
