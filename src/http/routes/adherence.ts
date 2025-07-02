import type { FastifyInstance } from 'fastify';
import { formatCivilDate } from '../../domain/time/civil.js';
import { adherenceService } from '../../app/adherence.service.js';
import type { Ports } from '../../app/ports.js';
import { requireSelfOrAdmin } from '../auth.js';
import { habitOf, memberOf, windowStringOf } from '../query.js';
import { resultView, streakView, summaryView } from '../views.js';

/**
 * The read routes a crew actually looks at: how a window went, what a run
 * looks like, and what is still open today.
 */
export function registerAdherenceRoutes(app: FastifyInstance, ports: Ports): void {
  const adherence = adherenceService(ports);

  app.get('/members/:memberId/adherence', async (request) => {
    const memberId = memberOf(request);
    requireSelfOrAdmin(request, memberId);
    const report = await adherence.report(memberId, windowStringOf(request, 'read adherence'));
    return {
      asOf: formatCivilDate(report.asOf),
      summary: summaryView(report.summary),
      byHabit: report.byHabit.map((entry) => ({
        habitId: entry.habitId,
        ...summaryView(entry),
      })),
      byHitch: report.byHitch.map(summaryView),
      byRole: report.byRole.map((split) => ({
        role: split.role,
        owed: split.owed,
        met: split.met,
        rate: Math.round(split.rate * 100) / 100,
      })),
      trend: report.trend,
      busiestHour: report.busiestHour ?? null,
      results: report.results.map(resultView),
    };
  });

  app.get('/members/:memberId/habits/:habitId/streak', async (request) => {
    const memberId = memberOf(request);
    requireSelfOrAdmin(request, memberId);
    const summary = await adherence.streak(
      memberId,
      habitOf(request),
      windowStringOf(request, 'read a streak'),
    );
    return streakView(summary);
  });

  app.get('/members/:memberId/open', async (request) => {
    const memberId = memberOf(request);
    requireSelfOrAdmin(request, memberId);
    const open = await adherence.open(memberId, windowStringOf(request, 'read what is open'));
    return { open: open.map(resultView) };
  });
}
