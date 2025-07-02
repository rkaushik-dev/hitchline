import type { FastifyInstance } from 'fastify';
import { crewMemberId } from '../../domain/ids.js';
import { formatCivilDate } from '../../domain/time/civil.js';
import { formatDateRange } from '../../domain/time/range.js';
import { rosterService } from '../../app/roster.service.js';
import type { Ports } from '../../app/ports.js';
import { requireScope, requireSelfOrAdmin } from '../auth.js';
import { memberOf, rawDateOf, windowStringOf } from '../query.js';
import { holdSchema, parseBody, shieldSchema } from '../schemas.js';
import { overrideView, shieldView } from '../views.js';

/**
 * Roster routes. Reading a roster is open to anybody who may read the member;
 * changing one needs the write scope, because a hold moves what the person is
 * measured against.
 */
export function registerRosterRoutes(app: FastifyInstance, ports: Ports): void {
  const rosters = rosterService(ports);

  app.get('/members/:memberId/roster', async (request) => {
    const memberId = memberOf(request);
    requireSelfOrAdmin(request, memberId);
    const days = await rosters.daysFor(memberId, windowStringOf(request, 'read a roster'));
    return {
      days: days.map((day) => ({
        date: formatCivilDate(day.date),
        role: day.role,
        source: day.source,
        zone: day.zone,
      })),
    };
  });

  app.get('/members/:memberId/hitch', async (request) => {
    const memberId = memberOf(request);
    requireSelfOrAdmin(request, memberId);
    const on = rawDateOf(request, 'on', 'find a hitch');
    return { hitch: formatDateRange(await rosters.hitchOn(memberId, on)) };
  });

  app.post('/roster/holds', async (request, reply) => {
    requireScope(request, 'write');
    const body = parseBody(holdSchema, request.body);
    const memberId = crewMemberId(body.memberId);
    requireSelfOrAdmin(request, memberId);
    const entry = await rosters.hold({
      memberId,
      range: body.range,
      role: body.role,
      reason: body.reason,
      note: body.note,
    });
    return reply.status(201).send(overrideView(entry));
  });

  app.post('/roster/shields', async (request, reply) => {
    requireScope(request, 'write');
    const body = parseBody(shieldSchema, request.body);
    const memberId = crewMemberId(body.memberId);
    requireSelfOrAdmin(request, memberId);
    const entry = await rosters.shieldDays({
      memberId,
      range: body.range,
      reason: body.reason,
      note: body.note,
      grantedBy: body.grantedBy === undefined ? undefined : crewMemberId(body.grantedBy),
    });
    return reply.status(201).send(shieldView(entry));
  });
}
