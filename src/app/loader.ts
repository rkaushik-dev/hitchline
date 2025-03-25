import { NotFoundError } from '../domain/errors.js';
import type { CrewMemberId } from '../domain/ids.js';
import type { Habit } from '../domain/habit/habit.js';
import { resolveAll, type ResolvedCheckIn } from '../domain/checkin/resolve.js';
import { roster, type Roster } from '../domain/rotation/roster.js';
import { expandHabit } from '../domain/schedule/expand.js';
import { evaluateAll, type ObligationResult } from '../domain/adherence/match.js';
import type { Shield } from '../domain/adherence/shield.js';
import { civilDateIn } from '../domain/time/zone.js';
import type { CivilDate } from '../domain/time/civil.js';
import type { DateRange } from '../domain/time/range.js';
import type { Ports } from './ports.js';

/**
 * One member, loaded whole. Three services used to assemble this for
 * themselves and they drifted: the nudge planner counted voided check ins for
 * a fortnight because only two of the three copies filtered them out.
 */
export interface LoadedMember {
  readonly roster: Roster;
  readonly habits: readonly Habit[];
  readonly resolved: readonly ResolvedCheckIn[];
  readonly shields: readonly Shield[];
  /** The date it is where the person is working, used to settle obligations. */
  readonly asOf: CivilDate;
}

export interface MemberLoader {
  load(memberId: CrewMemberId): Promise<LoadedMember>;
  score(loaded: LoadedMember, window: DateRange): ObligationResult[];
  scoreOne(loaded: LoadedMember, habit: Habit, window: DateRange): ObligationResult[];
}

export function memberLoader(ports: Ports): MemberLoader {
  return {
    async load(memberId: CrewMemberId): Promise<LoadedMember> {
      const member = await ports.members.byId(memberId);
      if (member === undefined) throw new NotFoundError('crew member', memberId);
      const rows = await ports.checkIns.byMember(memberId);
      return {
        roster: roster(member, await ports.overrides.byMember(memberId)),
        habits: await ports.habits.byMember(memberId),
        resolved: resolveAll(
          member,
          rows.filter((entry) => entry.voidedAt === undefined),
        ),
        shields: await ports.shields.byMember(memberId),
        asOf: civilDateIn(member.siteZone, ports.clock.now()),
      };
    },

    score(loaded: LoadedMember, window: DateRange): ObligationResult[] {
      const results: ObligationResult[] = [];
      for (const habit of loaded.habits) {
        results.push(...this.scoreOne(loaded, habit, window));
      }
      return results;
    },

    scoreOne(loaded: LoadedMember, habit: Habit, window: DateRange): ObligationResult[] {
      const obligations = expandHabit({ roster: loaded.roster, habit, window });
      return evaluateAll(habit, obligations, loaded.resolved, loaded.asOf);
    },
  };
}

export function habitOf(loaded: LoadedMember, habitId: string): Habit {
  const found = loaded.habits.find((habit) => habit.id === habitId);
  if (found === undefined) throw new NotFoundError('habit', habitId);
  return found;
}
