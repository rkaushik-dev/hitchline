import type { FastifyInstance } from 'fastify';
import { ValidationError } from '../../domain/errors.js';
import { crewId as parseCrewId } from '../../domain/ids.js';
import { compareService } from '../../app/compare.service.js';
import type { Ports } from '../../app/ports.js';
import { requireScope } from '../auth.js';

/**
 * The crew comparison. It needs the read scope and nothing more, because the
 * service already drops anything the viewer is not allowed to see and says
 * how much it dropped.
 */
export function registerCompareRoutes(app: FastifyInstance, ports: Ports): void {
  const compare = compareService(ports);

  app.get('/crews/:crewId/comparison', async (request) => {
    const principal = requireScope(request, 'read');
    const params = request.params as { crewId: string };
    const query = request.query as { window?: string; on?: string };
    if (query.window === undefined) {
      throw ValidationError.field('window', 'is needed to compare a crew');
    }
    if (query.on === undefined) {
      throw ValidationError.field('on', 'is needed to read the crew as it was that day');
    }
    const report = await compare.crew(
      principal.memberId,
      parseCrewId(params.crewId),
      query.window,
      query.on,
    );
    return {
      crewId: report.crewId,
      rate: Math.round(report.rate * 100) / 100,
      lines: report.lines.map((line) => ({
        memberId: line.memberId,
        displayName: line.displayName,
        owed: line.owed,
        met: line.met,
        missed: line.missed,
        rate: Math.round(line.rate * 100) / 100,
        hidden: line.hidden,
      })),
    };
  });
}
