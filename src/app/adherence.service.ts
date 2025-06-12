import type { CrewMemberId, HabitId } from '../domain/ids.js';
import type { ObligationResult } from '../domain/adherence/match.js';
import { computeStreak, type StreakSummary } from '../domain/adherence/streak.js';
import {
  busiestHour,
  byRole,
  trendOf,
  type RoleSplit,
  type Trend,
} from '../domain/adherence/analytics.js';
import { roleFor } from '../domain/rotation/roster.js';
import {
  summarise,
  summariseByHabit,
  summariseByHitch,
  type HabitSummary,
  type PeriodSummary,
} from '../domain/adherence/summary.js';
import { parseDateRange, type DateRange } from '../domain/time/range.js';
import type { CivilDate } from '../domain/time/civil.js';
import { habitOf, memberLoader, type LoadedMember } from './loader.js';
import type { Ports } from './ports.js';

export interface AdherenceReport {
  readonly window: DateRange;
  readonly asOf: CivilDate;
  readonly results: readonly ObligationResult[];
  readonly summary: PeriodSummary;
  readonly byHabit: readonly HabitSummary[];
  readonly byHitch: readonly PeriodSummary[];
  /** How the same person does on shift against at home. */
  readonly byRole: readonly RoleSplit[];
  readonly trend: Trend;
  readonly busiestHour: number | undefined;
}

export interface AdherenceService {
  report(memberId: CrewMemberId, window: string): Promise<AdherenceReport>;
  streak(memberId: CrewMemberId, habitId: HabitId, window: string): Promise<StreakSummary>;
  open(memberId: CrewMemberId, window: string): Promise<ObligationResult[]>;
}

/**
 * The read side. Loading a member is somebody else's job now; this file is
 * only about turning what was loaded into the shapes a report wants.
 */
export function adherenceService(ports: Ports): AdherenceService {
  const loader = memberLoader(ports);

  return {
    async report(memberId: CrewMemberId, window: string): Promise<AdherenceReport> {
      const range = parseDateRange(window);
      const loaded: LoadedMember = await loader.load(memberId);
      const results = loader.score(loaded, range);
      const request = {
        roster: loaded.roster,
        results,
        shields: loaded.shields,
        range,
      };
      const byHitch = summariseByHitch(request);
      return {
        window: range,
        asOf: loaded.asOf,
        results,
        summary: summarise(request),
        byHabit: summariseByHabit(request),
        byHitch,
        byRole: byRole(results, (result) => roleFor(loaded.roster, result.obligation.dueOn)),
        trend: trendOf(byHitch),
        busiestHour: busiestHour(loaded.resolved)?.hour,
      };
    },

    async streak(memberId: CrewMemberId, habitId: HabitId, window: string): Promise<StreakSummary> {
      const range = parseDateRange(window);
      const loaded = await loader.load(memberId);
      const results = loader.scoreOne(loaded, habitOf(loaded, habitId), range);
      return computeStreak({ results, shields: loaded.shields, memberId });
    },

    async open(memberId: CrewMemberId, window: string): Promise<ObligationResult[]> {
      const range = parseDateRange(window);
      const loaded = await loader.load(memberId);
      return loader
        .score(loaded, range)
        .filter((result) => result.status === 'pending' && result.count < result.obligation.times);
    },
  };
}
