import { NotFoundError } from '../domain/errors.js';
import type { CrewMemberId, HabitId } from '../domain/ids.js';
import type { Habit } from '../domain/habit/habit.js';
import { resolveAll, type ResolvedCheckIn } from '../domain/checkin/resolve.js';
import { roster, type Roster } from '../domain/rotation/roster.js';
import { expandHabit } from '../domain/schedule/expand.js';
import { evaluateAll, type ObligationResult } from '../domain/adherence/match.js';
import { computeStreak, type StreakSummary } from '../domain/adherence/streak.js';
import type { Shield } from '../domain/adherence/shield.js';
import {
  summarise,
  summariseByHabit,
  summariseByHitch,
  type HabitSummary,
  type PeriodSummary,
} from '../domain/adherence/summary.js';
import { civilDateIn } from '../domain/time/zone.js';
import { parseDateRange, type DateRange } from '../domain/time/range.js';
import type { CivilDate } from '../domain/time/civil.js';
import type { Ports } from './ports.js';

export interface AdherenceReport {
  readonly window: DateRange;
  readonly asOf: CivilDate;
  readonly results: readonly ObligationResult[];
  readonly summary: PeriodSummary;
  readonly byHabit: readonly HabitSummary[];
  readonly byHitch: readonly PeriodSummary[];
}

export interface AdherenceService {
  report(memberId: CrewMemberId, window: string): Promise<AdherenceReport>;
  streak(memberId: CrewMemberId, habitId: HabitId, window: string): Promise<StreakSummary>;
  open(memberId: CrewMemberId, window: string): Promise<ObligationResult[]>;
}

interface Loaded {
  readonly roster: Roster;
  readonly habits: readonly Habit[];
  readonly resolved: readonly ResolvedCheckIn[];
  readonly shields: readonly Shield[];
  readonly asOf: CivilDate;
}

/**
 * The read side. It pulls the roster, the habits, the ledger and the shields
 * together, expands what was owed and scores it. Nothing here decides anything
 * on its own; it is the wiring that lets the domain rules meet real rows.
 */
export function adherenceService(ports: Ports): AdherenceService {
  async function load(memberId: CrewMemberId): Promise<Loaded> {
    const member = await ports.members.byId(memberId);
    if (member === undefined) throw new NotFoundError('crew member', memberId);
    const overrides = await ports.overrides.byMember(memberId);
    const rows = await ports.checkIns.byMember(memberId);
    const live = rows.filter((entry) => entry.voidedAt === undefined);
    return {
      roster: roster(member, overrides),
      habits: await ports.habits.byMember(memberId),
      resolved: resolveAll(member, live),
      shields: await ports.shields.byMember(memberId),
      asOf: civilDateIn(member.siteZone, ports.clock.now()),
    };
  }

  function score(loaded: Loaded, window: DateRange): ObligationResult[] {
    const results: ObligationResult[] = [];
    for (const habit of loaded.habits) {
      const obligations = expandHabit({ roster: loaded.roster, habit, window });
      results.push(...evaluateAll(habit, obligations, loaded.resolved, loaded.asOf));
    }
    return results;
  }

  return {
    async report(memberId: CrewMemberId, window: string): Promise<AdherenceReport> {
      const range = parseDateRange(window);
      const loaded = await load(memberId);
      const results = score(loaded, range);
      const request = {
        roster: loaded.roster,
        results,
        shields: loaded.shields,
        range,
      };
      return {
        window: range,
        asOf: loaded.asOf,
        results,
        summary: summarise(request),
        byHabit: summariseByHabit(request),
        byHitch: summariseByHitch(request),
      };
    },

    async streak(memberId: CrewMemberId, habitId: HabitId, window: string): Promise<StreakSummary> {
      const range = parseDateRange(window);
      const loaded = await load(memberId);
      const habit = loaded.habits.find((entry) => entry.id === habitId);
      if (habit === undefined) throw new NotFoundError('habit', habitId);
      const obligations = expandHabit({ roster: loaded.roster, habit, window: range });
      const results = evaluateAll(habit, obligations, loaded.resolved, loaded.asOf);
      return computeStreak({ results, shields: loaded.shields, memberId });
    },

    async open(memberId: CrewMemberId, window: string): Promise<ObligationResult[]> {
      const range = parseDateRange(window);
      const loaded = await load(memberId);
      return score(loaded, range).filter(
        (result) => result.status === 'pending' && result.count < result.obligation.times,
      );
    },
  };
}
