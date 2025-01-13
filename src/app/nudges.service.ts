import { NotFoundError } from '../domain/errors.js';
import type { CrewMemberId } from '../domain/ids.js';
import { roster } from '../domain/rotation/roster.js';
import { resolveAll } from '../domain/checkin/resolve.js';
import { expandHabit } from '../domain/schedule/expand.js';
import { evaluateAll, type ObligationResult } from '../domain/adherence/match.js';
import { computeStreak } from '../domain/adherence/streak.js';
import { isAtRisk } from '../domain/adherence/streak.js';
import {
  DEFAULT_POLICY,
  nudgePolicy,
  type NudgePolicy,
  type NudgePolicyInput,
} from '../domain/nudge/policy.js';
import { planNudges, type PlannedNudge } from '../domain/nudge/planner.js';
import { civilDateIn } from '../domain/time/zone.js';
import { parseDateRange } from '../domain/time/range.js';
import type { Ports } from './ports.js';

export interface NudgesService {
  policyFor(memberId: CrewMemberId): Promise<NudgePolicy>;
  setPolicy(memberId: CrewMemberId, input: NudgePolicyInput): Promise<NudgePolicy>;
  plan(memberId: CrewMemberId, window: string): Promise<PlannedNudge[]>;
  atRisk(memberId: CrewMemberId, window: string): Promise<boolean>;
}

/**
 * Planning the pings. The service reads the same obligations the adherence
 * report does, so a nudge can never claim something is owed that the report
 * does not also show as open.
 */
export function nudgesService(ports: Ports): NudgesService {
  async function scored(memberId: CrewMemberId, window: string): Promise<ObligationResult[]> {
    const member = await ports.members.byId(memberId);
    if (member === undefined) throw new NotFoundError('crew member', memberId);
    const range = parseDateRange(window);
    const target = roster(member, await ports.overrides.byMember(memberId));
    const rows = await ports.checkIns.byMember(memberId);
    const resolved = resolveAll(
      member,
      rows.filter((entry) => entry.voidedAt === undefined),
    );
    const asOf = civilDateIn(member.siteZone, ports.clock.now());
    const results: ObligationResult[] = [];
    for (const habit of await ports.habits.byMember(memberId)) {
      const obligations = expandHabit({ roster: target, habit, window: range });
      results.push(...evaluateAll(habit, obligations, resolved, asOf));
    }
    return results;
  }

  return {
    async policyFor(memberId: CrewMemberId): Promise<NudgePolicy> {
      const stored = await ports.policies.byMember(memberId);
      return stored ?? DEFAULT_POLICY;
    },

    async setPolicy(memberId: CrewMemberId, input: NudgePolicyInput): Promise<NudgePolicy> {
      const member = await ports.members.byId(memberId);
      if (member === undefined) throw new NotFoundError('crew member', memberId);
      const policy = nudgePolicy(input);
      await ports.policies.save(memberId, policy);
      return policy;
    },

    async plan(memberId: CrewMemberId, window: string): Promise<PlannedNudge[]> {
      const member = await ports.members.byId(memberId);
      if (member === undefined) throw new NotFoundError('crew member', memberId);
      const results = await scored(memberId, window);
      const policy = (await ports.policies.byMember(memberId)) ?? DEFAULT_POLICY;
      return planNudges({
        roster: roster(member, await ports.overrides.byMember(memberId)),
        policy,
        results,
        asOf: ports.clock.now(),
      });
    },

    async atRisk(memberId: CrewMemberId, window: string): Promise<boolean> {
      const results = await scored(memberId, window);
      const shields = await ports.shields.byMember(memberId);
      const summary = computeStreak({ results, shields, memberId });
      return isAtRisk(summary, results);
    },
  };
}
