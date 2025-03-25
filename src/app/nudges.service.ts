import { NotFoundError } from '../domain/errors.js';
import type { CrewMemberId } from '../domain/ids.js';
import { computeStreak, isAtRisk } from '../domain/adherence/streak.js';
import {
  DEFAULT_POLICY,
  nudgePolicy,
  type NudgePolicy,
  type NudgePolicyInput,
} from '../domain/nudge/policy.js';
import { planNudges, type PlannedNudge } from '../domain/nudge/planner.js';
import { parseDateRange } from '../domain/time/range.js';
import { memberLoader } from './loader.js';
import type { Ports } from './ports.js';

export interface NudgesService {
  policyFor(memberId: CrewMemberId): Promise<NudgePolicy>;
  setPolicy(memberId: CrewMemberId, input: NudgePolicyInput): Promise<NudgePolicy>;
  plan(memberId: CrewMemberId, window: string): Promise<PlannedNudge[]>;
  atRisk(memberId: CrewMemberId, window: string): Promise<boolean>;
}

/**
 * Planning the pings. The service reads the member through the same loader the
 * adherence report uses, so a nudge can never claim something is owed that the
 * report does not also show as open.
 */
export function nudgesService(ports: Ports): NudgesService {
  const loader = memberLoader(ports);

  return {
    async policyFor(memberId: CrewMemberId): Promise<NudgePolicy> {
      return (await ports.policies.byMember(memberId)) ?? DEFAULT_POLICY;
    },

    async setPolicy(memberId: CrewMemberId, input: NudgePolicyInput): Promise<NudgePolicy> {
      const member = await ports.members.byId(memberId);
      if (member === undefined) throw new NotFoundError('crew member', memberId);
      const policy = nudgePolicy(input);
      await ports.policies.save(memberId, policy);
      return policy;
    },

    async plan(memberId: CrewMemberId, window: string): Promise<PlannedNudge[]> {
      const loaded = await loader.load(memberId);
      const results = loader.score(loaded, parseDateRange(window));
      const policy = (await ports.policies.byMember(memberId)) ?? DEFAULT_POLICY;
      return planNudges({
        roster: loaded.roster,
        policy,
        results,
        asOf: ports.clock.now(),
      });
    },

    async atRisk(memberId: CrewMemberId, window: string): Promise<boolean> {
      const loaded = await loader.load(memberId);
      const results = loader.score(loaded, parseDateRange(window));
      const summary = computeStreak({ results, shields: loaded.shields, memberId });
      return isAtRisk(summary, results);
    },
  };
}
