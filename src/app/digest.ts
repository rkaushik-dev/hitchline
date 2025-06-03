import type { CrewMember } from '../domain/crew/member.js';
import type { Habit } from '../domain/habit/habit.js';
import type { ObligationResult } from '../domain/adherence/match.js';
import type { StreakSummary } from '../domain/adherence/streak.js';
import type { PeriodSummary } from '../domain/adherence/summary.js';
import { formatDateRange } from '../domain/time/range.js';

/**
 * The end of hitch note. A crew member coming ashore wants four lines about
 * how it went, not a dashboard, and the same four lines are what a lead reads
 * over somebody's shoulder. It is plain text because that survives every
 * handset, mail client and printout on a rig.
 */
export interface DigestInput {
  readonly member: CrewMember;
  readonly summary: PeriodSummary;
  readonly habits: readonly Habit[];
  readonly results: readonly ObligationResult[];
  readonly streaks: ReadonlyMap<string, StreakSummary>;
}

export interface HabitLine {
  readonly name: string;
  readonly owed: number;
  readonly met: number;
  readonly streak: number;
}

export function habitLines(input: DigestInput): HabitLine[] {
  return input.habits
    .map((habit) => {
      const mine = input.results.filter((result) => result.obligation.habitId === habit.id);
      return {
        name: habit.name,
        owed: mine.filter((result) => result.status !== 'pending').length,
        met: mine.filter((result) => result.status === 'met').length,
        streak: input.streaks.get(habit.id)?.current ?? 0,
      };
    })
    .filter((line) => line.owed > 0)
    .sort((left, right) => right.met - left.met || left.name.localeCompare(right.name));
}

function share(met: number, owed: number): string {
  if (owed === 0) return 'nothing was owed';
  return `${String(met)} of ${String(owed)}`;
}

/** The opening line, which is the only line most people read. */
export function headline(input: DigestInput): string {
  const { summary } = input;
  const window = formatDateRange(summary.range);
  if (summary.owed === 0) {
    return `${input.member.displayName}, nothing was owed over ${window}.`;
  }
  if (summary.missed === 0 && summary.partial === 0) {
    return `${input.member.displayName}, you cleared everything over ${window}.`;
  }
  return `${input.member.displayName}, you did ${share(summary.met, summary.owed - summary.pending)} over ${window}.`;
}

export function digest(input: DigestInput): string {
  const lines: string[] = [headline(input), ''];

  for (const line of habitLines(input)) {
    const run = line.streak === 0 ? '' : `, ${String(line.streak)} in a row`;
    lines.push(`- ${line.name}: ${share(line.met, line.owed)}${run}`);
  }

  if (input.summary.shieldedDays > 0) {
    lines.push('');
    lines.push(
      `${String(input.summary.shieldedDays)} days were shielded and are not held against you.`,
    );
  }

  const best = habitLines(input).reduce<HabitLine | undefined>(
    (found, line) => (found === undefined || line.streak > found.streak ? line : found),
    undefined,
  );
  if (best !== undefined && best.streak > 2) {
    lines.push('');
    lines.push(`Longest run this hitch: ${best.name} at ${String(best.streak)}.`);
  }

  return `${lines.join('\n').trimEnd()}\n`;
}

export function subjectLine(input: DigestInput): string {
  return `hitchline: ${formatDateRange(input.summary.range)} for ${input.member.displayName}`;
}
