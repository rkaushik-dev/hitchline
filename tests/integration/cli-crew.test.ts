import { beforeEach, describe, expect, it } from 'vitest';
import { buildProgram } from '../../src/cli/main.js';
import { EXIT_FAILED, EXIT_OK, recordingContext, type Recorder } from '../../src/cli/context.js';
import { readConfig } from '../../src/config/env.js';
import { memoryStore, type MemoryStore } from '../../src/infra/store/memory.js';
import { fixedClock } from '../../src/infra/clock.js';
import { sequentialIds } from '../../src/infra/id.js';

const config = { ...readConfig({}), store: 'memory' as const };
let store: MemoryStore;
let context: Recorder;

beforeEach(() => {
  store = memoryStore({ clock: fixedClock('2025-02-10T09:00:00Z'), ids: sequentialIds() });
  context = recordingContext(store, config);
});

async function run(...argv: string[]): Promise<number> {
  process.exitCode = EXIT_OK;
  const program = buildProgram(() => context);
  program.exitOverride();
  await program.parseAsync(['node', 'hitchline', ...argv]);
  return process.exitCode ?? EXIT_OK;
}

describe('demo seed', () => {
  it('fills the store and reports what it wrote', async () => {
    expect(await run('demo', 'seed')).toBe(EXIT_OK);
    expect(context.text()).toContain('members    5');
    expect(context.text()).toContain('habits     20');
    expect(await store.members.all()).toHaveLength(5);
  });

  it('takes a length and a diligence', async () => {
    await run('demo', 'seed', '--days', '7', '--adherence', '1');
    expect(context.text()).toContain('to         2025-01-12');
  });
});

describe('crew commands', () => {
  it('starts a crew and prints its id', async () => {
    const code = await run(
      'crew',
      'add',
      '--name',
      'Alpha production',
      '--site',
      'Grane B',
      '--zone',
      'Europe/Oslo',
    );
    expect(code).toBe(EXIT_OK);
    expect(context.text()).toContain('Alpha production at Grane B');
  });

  it('refuses a zone it does not know', async () => {
    const code = await run(
      'crew',
      'add',
      '--name',
      'Alpha',
      '--site',
      'Grane B',
      '--zone',
      'North Sea/Alpha',
    );
    expect(code).toBeGreaterThan(EXIT_OK);
  });

  it('enrols somebody and lists them', async () => {
    await run('demo', 'seed', '--days', '7');
    const crewId = (await store.crews.all())[0]?.id ?? '';
    context.clear();
    await run('crew', 'members', crewId, '--on', '2025-01-10');
    const lines = context.text().split('\n');
    expect(lines[0]).toContain('name');
    expect(lines).toHaveLength(7);
  });

  it('reports a crew it does not hold', async () => {
    const code = await run('crew', 'members', 'crw_9999999999999999', '--on', '2025-01-10');
    expect(code).toBe(EXIT_OK);
    expect(context.text().split('\n')).toHaveLength(2);
  });

  it('compares the crew as one member sees it', async () => {
    await run('demo', 'seed', '--days', '14', '--adherence', '1');
    const crewId = (await store.crews.all())[0]?.id ?? '';
    const lead = (await store.members.all())[0]?.id ?? '';
    context.clear();
    const code = await run(
      'crew',
      'compare',
      crewId,
      lead,
      '--window',
      '2025-01-06..2025-01-19',
      '--on',
      '2025-01-10',
    );
    expect(code).toBe(EXIT_OK);
    expect(context.text()).toContain('hidden');
    expect(context.text()).toContain('crew');
  });

  it('refuses a comparison with no window', async () => {
    await run('demo', 'seed', '--days', '7');
    const crewId = (await store.crews.all())[0]?.id ?? '';
    const lead = (await store.members.all())[0]?.id ?? '';
    context.clear();
    await expect(run('crew', 'compare', crewId, lead, '--on', '2025-01-10')).rejects.toThrow();
  });

  it('reports an enrolment clash', async () => {
    await run('demo', 'seed', '--days', '7');
    const crewId = (await store.crews.all())[0]?.id ?? '';
    const member = (await store.members.all())[0]?.id ?? '';
    context.clear();
    const code = await run('crew', 'enrol', crewId, member, '--from', '2025-06-01');
    expect(code).toBe(EXIT_FAILED);
    expect(context.errorText()).toContain('conflict');
  });
});
