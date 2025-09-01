import { describe, expect, it } from 'vitest';
import * as hitchline from '../../src/index.js';

/**
 * The surface other people build against. A name leaving this list is a
 * breaking change, so the test spells out what is on it rather than counting.
 */
describe('the public surface', () => {
  it('exports the rotation building blocks', () => {
    expect(typeof hitchline.parseRotationPattern).toBe('function');
    expect(typeof hitchline.rotationAssignment).toBe('function');
    expect(typeof hitchline.roster).toBe('function');
    expect(typeof hitchline.rotationDayAt).toBe('function');
  });

  it('exports habits, cadences and expansion', () => {
    expect(typeof hitchline.habit).toBe('function');
    expect(typeof hitchline.parseCadence).toBe('function');
    expect(typeof hitchline.expandHabit).toBe('function');
    expect(typeof hitchline.findTemplate).toBe('function');
  });

  it('exports scoring, streaks and summaries', () => {
    expect(typeof hitchline.evaluateAll).toBe('function');
    expect(typeof hitchline.computeStreak).toBe('function');
    expect(typeof hitchline.computeFrozenStreak).toBe('function');
    expect(typeof hitchline.summarise).toBe('function');
    expect(typeof hitchline.readQuota).toBe('function');
  });

  it('exports the services', () => {
    for (const name of [
      'habitsService',
      'checkInsService',
      'rosterService',
      'adherenceService',
      'nudgesService',
      'crewService',
      'compareService',
      'searchService',
      'importService',
    ] as const) {
      expect(typeof hitchline[name]).toBe('function');
    }
  });

  it('exports the stores and the two surfaces', () => {
    expect(typeof hitchline.memoryStore).toBe('function');
    expect(typeof hitchline.fileStore).toBe('function');
    expect(typeof hitchline.buildApp).toBe('function');
    expect(typeof hitchline.buildProgram).toBe('function');
  });

  it('exports the errors a caller has to handle', () => {
    expect(typeof hitchline.ValidationError).toBe('function');
    expect(typeof hitchline.NotFoundError).toBe('function');
    expect(typeof hitchline.isDomainError).toBe('function');
  });

  it('names the api version it was built at', () => {
    expect(hitchline.API_VERSION).toMatch(/^\d+\.\d+\.\d+$/);
  });

  it('works end to end through the surface alone', async () => {
    const store = hitchline.memoryStore({
      clock: hitchline.fixedClock('2025-07-24T09:00:00Z'),
      ids: hitchline.sequentialIds(),
    });
    const seeded = await hitchline.seedDemo(store, { from: '2025-01-06', days: 14, adherence: 1 });
    const report = await hitchline
      .adherenceService(store)
      .report(hitchline.crewMemberId('mem_0000000000000001'), '2025-01-06..2025-01-19');
    expect(seeded.members).toBe(5);
    expect(report.summary.owed).toBeGreaterThan(0);
    expect(report.summary.missed).toBe(0);
  });
});

describe('the wider surface', () => {
  it('exports the crew and sharing rules', () => {
    expect(typeof hitchline.crew).toBe('function');
    expect(typeof hitchline.membership).toBe('function');
    expect(typeof hitchline.canRead).toBe('function');
    expect(typeof hitchline.memberPreferences).toBe('function');
  });

  it('exports the nudge policy, the plan and the dispatch record', () => {
    expect(typeof hitchline.nudgePolicy).toBe('function');
    expect(typeof hitchline.planNudges).toBe('function');
    expect(typeof hitchline.planDispatch).toBe('function');
    expect(typeof hitchline.shouldRetry).toBe('function');
  });

  it('exports the rotation calendar', () => {
    expect(typeof hitchline.buildCalendar).toBe('function');
    expect(typeof hitchline.blocksIn).toBe('function');
  });

  it('exports the import and export helpers', () => {
    expect(typeof hitchline.checkInsToCsv).toBe('function');
    expect(typeof hitchline.obligationsToCalendar).toBe('function');
    expect(typeof hitchline.readTable).toBe('function');
  });

  it('exports the manifest and the error catalogue', () => {
    expect(Array.isArray(hitchline.ROUTES)).toBe(true);
    expect(Array.isArray(hitchline.CATALOGUE)).toBe(true);
    expect(typeof hitchline.openApiDocument).toBe('function');
    expect(typeof hitchline.apiMarkdown).toBe('function');
  });

  it('exports the sync path a handset uses', () => {
    expect(typeof hitchline.syncService).toBe('function');
    expect(typeof hitchline.settledIds).toBe('function');
  });

  it('does not leak the internals of the store', () => {
    expect('openJournal' in hitchline).toBe(false);
    expect('takeLock' in hitchline).toBe(false);
  });
});
