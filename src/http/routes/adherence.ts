import type { FastifyInstance } from 'fastify';
import { ValidationError } from '../../domain/errors.js';
import { crewMemberId, habitId } from '../../domain/ids.js';
import { formatCivilDate } from '../../domain/time/civil.js';
import { adherenceService } from '../../app/adherence.service.js';
import type { Ports } from '../../app/ports.js';
import { requireSelfOrAdmin } from '../auth.js';
import { resultView, streakView, summaryView } from '../views.js';

/**
 * The read routes a crew actually looks at: how a window went, what a run
 * looks like, and what is still open today.
 */
export function registerAdherenceRoutes(app: FastifyInstance, ports: Ports): void {
  const adherence = adherenceService(ports);

  function windowOf(query: unknown): string {
    const window = (query as { window?: string }).window;
    if (window === undefined) {
      throw ValidationError.field('window', 'is needed to read adherence');
    }
    return window;
  }

  app.get('/members/:memberId/adherence', async (request) => {
    const params = request.params as { memberId: string };
    const memberId = crewMemberId(params.memberId);
    requireSelfOrAdmin(request, memberId);
    const report = await adherence.report(memberId, windowOf(request.query));
    return {
      asOf: formatCivilDate(report.asOf),
      summary: summaryView(report.summary),
      byHabit: report.byHabit.map((entry) => ({
        habitId: entry.habitId,
        ...summaryView(entry),
      })),
      byHitch: report.byHitch.map(summaryView),
      results: report.results.map(resultView),
    };
  });

  app.get('/members/:memberId/habits/:habitId/streak', async (request) => {
    const params = request.params as { memberId: string; habitId: string };
    const memberId = crewMemberId(params.memberId);
    requireSelfOrAdmin(request, memberId);
    const summary = await adherence.streak(
      memberId,
      habitId(params.habitId),
      windowOf(request.query),
    );
    return streakView(summary);
  });

  app.get('/members/:memberId/open', async (request) => {
    const params = request.params as { memberId: string };
    const memberId = crewMemberId(params.memberId);
    requireSelfOrAdmin(request, memberId);
    const open = await adherence.open(memberId, windowOf(request.query));
    return { open: open.map(resultView) };
  });
}
