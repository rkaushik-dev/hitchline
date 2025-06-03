import { describe, expect, it } from 'vitest';
import {
  digest,
  habitLines,
  headline,
  subjectLine,
  type DigestInput,
} from '../../../src/app/digest.js';
import type { AdherenceStatus, ObligationResult } from '../../../src/domain/adherence/match.js';
import { NO_STREAK, type StreakSummary } from '../../../src/domain/adherence/streak.js';
import { obligation } from '../../../src/domain/schedule/obligation.js';
import { crewMemberId, habitId } from '../../../src/domain/ids.js';
import { parseDateRange } from '../../../src/domain/time/range.js';
import { aHabit, aMember } from '../../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const treadmill = habitId('hab_0123456789ABCDEF');
const sleep = habitId('hab_FEDCBA9876543210');
const window = parseDateRange('2025-06-02..2025-06-15');

function result(habit: typeof treadmill, day: string, status: AdherenceStatus): ObligationResult {
  return {
    obligation: obligation({
      habitId: habit,
      memberId: ada,
      scope: 'day',
      window: parseDateRange(`${day}..${day}`),
      times: 1,
    }),
    count: status === 'met' ? 1 : 0,
    total: undefined,
    status,
    progress: status === 'met' ? 1 : 0,
  };
}

function streaks(entries: Record<string, number>): Map<string, StreakSummary> {
  const map = new Map<string, StreakSummary>();
  for (const [id, current] of Object.entries(entries)) {
    map.set(id, { ...NO_STREAK, current, longest: current });
  }
  return map;
}

function anInput(overrides: Partial<DigestInput> = {}): DigestInput {
  return {
    member: aMember(),
    summary: {
      range: window,
      owed: 4,
      met: 3,
      partial: 0,
      missed: 1,
      pending: 0,
      shieldedDays: 0,
      rate: 0.75,
    },
    habits: [aHabit(), aHabit({ id: 'hab_FEDCBA9876543210', name: 'Bank the sleep hours' })],
    results: [
      result(treadmill, '2025-06-02', 'met'),
      result(treadmill, '2025-06-03', 'met'),
      result(sleep, '2025-06-02', 'met'),
      result(sleep, '2025-06-03', 'missed'),
    ],
    streaks: streaks({ [treadmill]: 4, [sleep]: 0 }),
    ...overrides,
  };
}

describe('the headline', () => {
  it('says what was done out of what was owed', () => {
    expect(headline(anInput())).toBe('Ada Vasquez, you did 3 of 4 over 2025-06-02..2025-06-15.');
  });

  it('says so when everything was cleared', () => {
    const clean = anInput({
      summary: { ...anInput().summary, met: 4, missed: 0, rate: 1 },
    });
    expect(headline(clean)).toContain('you cleared everything');
  });

  it('says so when nothing was owed', () => {
    const empty = anInput({
      summary: { ...anInput().summary, owed: 0, met: 0, missed: 0, rate: 1 },
    });
    expect(headline(empty)).toContain('nothing was owed');
  });

  it('leaves pending obligations out of the denominator', () => {
    const open = anInput({ summary: { ...anInput().summary, owed: 6, pending: 2 } });
    expect(headline(open)).toContain('3 of 4');
  });
});

describe('the habit lines', () => {
  it('gives one line per habit that owed something', () => {
    const lines = habitLines(anInput());
    expect(lines.map((line) => line.name)).toEqual([
      'Twenty minutes on the treadmill',
      'Bank the sleep hours',
    ]);
  });

  it('leaves out a habit that owed nothing', () => {
    const quiet = anInput({ results: [result(treadmill, '2025-06-02', 'met')] });
    expect(habitLines(quiet)).toHaveLength(1);
  });

  it('leaves pending obligations out of the count', () => {
    const open = anInput({
      results: [result(treadmill, '2025-06-02', 'met'), result(treadmill, '2025-06-04', 'pending')],
    });
    expect(habitLines(open)[0]?.owed).toBe(1);
  });

  it('carries the run from the streaks it was given', () => {
    expect(habitLines(anInput())[0]?.streak).toBe(4);
  });
});

describe('the whole digest', () => {
  it('opens with the headline and lists the habits', () => {
    const text = digest(anInput());
    const lines = text.split('\n');
    expect(lines[0]).toContain('Ada Vasquez');
    expect(lines[2]).toBe('- Twenty minutes on the treadmill: 2 of 2, 4 in a row');
    expect(lines[3]).toBe('- Bank the sleep hours: 1 of 2');
  });

  it('mentions shielded days when there were any', () => {
    const shielded = anInput({ summary: { ...anInput().summary, shieldedDays: 3 } });
    expect(digest(shielded)).toContain('3 days were shielded');
  });

  it('says nothing about shielded days when there were none', () => {
    expect(digest(anInput())).not.toContain('shielded');
  });

  it('calls out the longest run when it is worth mentioning', () => {
    expect(digest(anInput())).toContain(
      'Longest run this hitch: Twenty minutes on the treadmill at 4.',
    );
  });

  it('says nothing about a run of one or two', () => {
    const short = anInput({ streaks: streaks({ [treadmill]: 2, [sleep]: 0 }) });
    expect(digest(short)).not.toContain('Longest run');
  });

  it('ends with exactly one newline', () => {
    const text = digest(anInput());
    expect(text.endsWith('\n')).toBe(true);
    expect(text.endsWith('\n\n')).toBe(false);
  });
});

describe('the subject line', () => {
  it('names the window and the person', () => {
    expect(subjectLine(anInput())).toBe('hitchline: 2025-06-02..2025-06-15 for Ada Vasquez');
  });
});
