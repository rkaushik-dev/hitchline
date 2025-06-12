import { beforeEach, describe, expect, it } from 'vitest';
import { buildProgram } from '../../src/cli/main.js';
import {
  EXIT_FAILED,
  EXIT_MISUSED,
  EXIT_OK,
  recordingContext,
  type Recorder,
} from '../../src/cli/context.js';
import { readConfig } from '../../src/config/env.js';
import { memoryStore, type MemoryStore } from '../../src/infra/store/memory.js';
import { fixedClock } from '../../src/infra/clock.js';
import { sequentialIds } from '../../src/infra/id.js';
import { checkInsService } from '../../src/app/checkins.service.js';
import { rosterService } from '../../src/app/roster.service.js';
import { crewMemberId, habitId } from '../../src/domain/ids.js';
import { aHabit, aMember } from '../support/fixtures.js';

const ada = crewMemberId('mem_0123456789ABCDEF');
const treadmill = habitId('hab_0123456789ABCDEF');
const config = { ...readConfig({}), store: 'memory' as const };
let store: MemoryStore;
let context: Recorder;

beforeEach(async () => {
  store = memoryStore({ clock: fixedClock('2025-01-20T09:00:00Z'), ids: sequentialIds() });
  context = recordingContext(store, config);
  await store.members.save(aMember());
  await store.habits.save(aHabit({ cadence: '1/onshift-day', startsOn: '2025-01-06' }));
  const checkIns = checkInsService(store);
  await checkIns.record({ habitId: treadmill, at: '2025-01-06T09:00:00Z', source: 'supervisor' });
  await checkIns.record({ habitId: treadmill, at: '2025-01-07T09:00:00Z', source: 'supervisor' });
  context.clear();
});

async function run(...argv: string[]): Promise<number> {
  process.exitCode = EXIT_OK;
  const program = buildProgram(() => context);
  program.exitOverride();
  await program.parseAsync(['node', 'hitchline', ...argv]);
  return process.exitCode ?? EXIT_OK;
}

describe('the adherence report', () => {
  it('prints the counts for a window', async () => {
    const code = await run('report', 'adherence', ada, '--window', '2025-01-06..2025-01-19');
    expect(code).toBe(EXIT_OK);
    const text = context.text();
    expect(text).toContain('owed      14');
    expect(text).toContain('met       2');
    expect(text).toContain('missed    12');
  });

  it('draws a bar for the rate', async () => {
    await run('report', 'adherence', ada, '--window', '2025-01-06..2025-01-07');
    expect(context.text()).toContain('########## 100%');
  });

  it('breaks it down by hitch when asked', async () => {
    await run('report', 'adherence', ada, '--window', '2025-01-06..2025-02-02', '--by-hitch');
    const text = context.text();
    expect(text).toContain('window');
    expect(text).toContain('2025-01-06..2025-02-02');
  });

  it('refuses a window it cannot read', async () => {
    expect(await run('report', 'adherence', ada, '--window', 'january')).toBe(EXIT_MISUSED);
  });

  it('reports a member it does not hold', async () => {
    const code = await run(
      'report',
      'adherence',
      'mem_9999999999999999',
      '--window',
      '2025-01-06..2025-01-19',
    );
    expect(code).toBe(EXIT_FAILED);
  });
});

describe('the open report', () => {
  it('lists what is still open', async () => {
    await run('report', 'open', ada, '--window', '2025-01-06..2025-02-10');
    const lines = context.text().split('\n');
    expect(lines[0]).toContain('due');
    expect(lines.length).toBeGreaterThan(2);
  });

  it('prints only a header when nothing is open', async () => {
    await run('report', 'open', ada, '--window', '2025-01-06..2025-01-07');
    expect(context.text().split('\n')).toHaveLength(2);
  });
});

describe('the streak report', () => {
  it('prints the run', async () => {
    await run('report', 'streak', ada, treadmill, '--window', '2025-01-06..2025-01-07');
    expect(context.text()).toContain('current    2');
    expect(context.text()).toContain('longest    2');
  });

  it('shows a shielded gap', async () => {
    await rosterService(store).shieldDays({
      memberId: ada,
      range: '2025-01-08..2025-01-09',
      reason: 'annual_leave',
    });
    context.clear();
    await run('report', 'streak', ada, treadmill, '--window', '2025-01-06..2025-01-09');
    expect(context.text()).toContain('shielded   2');
  });

  it('reports a habit the member does not have', async () => {
    const code = await run(
      'report',
      'streak',
      ada,
      'hab_9999999999999999',
      '--window',
      '2025-01-06..2025-01-07',
    );
    expect(code).toBe(EXIT_FAILED);
  });
});

describe('the nudge plan', () => {
  it('prints what the planner would send', async () => {
    await run('report', 'nudges', ada, '--window', '2025-01-20..2025-02-05');
    expect(context.text().split('\n')[0]).toContain('day');
  });
});

describe('the shape of a window', () => {
  it('prints the role split when asked', async () => {
    await run('report', 'adherence', ada, '--window', '2025-01-06..2025-01-19', '--shape');
    const text = context.text();
    expect(text).toContain('role');
    expect(text).toContain('onshift');
  });

  it('says what the trend is', async () => {
    await run('report', 'adherence', ada, '--window', '2025-01-06..2025-01-19', '--shape');
    expect(context.text()).toContain('not enough hitches to say');
  });

  it('names the busiest hour', async () => {
    await run('report', 'adherence', ada, '--window', '2025-01-06..2025-01-19', '--shape');
    expect(context.text()).toContain('busiest hour  10:00');
  });

  it('says so when nothing has been recorded', async () => {
    const empty = memoryStore({ clock: fixedClock('2025-01-20T09:00:00Z'), ids: sequentialIds() });
    await empty.members.save(aMember());
    context = recordingContext(empty, config);
    await run('report', 'adherence', ada, '--window', '2025-01-06..2025-01-19', '--shape');
    expect(context.text()).toContain('nothing recorded');
  });

  it('leaves the shape out unless it is asked for', async () => {
    await run('report', 'adherence', ada, '--window', '2025-01-06..2025-01-19');
    expect(context.text()).not.toContain('busiest hour');
  });
});
