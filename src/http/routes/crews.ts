import type { FastifyInstance } from 'fastify';
import { ValidationError } from '../../domain/errors.js';
import { crewId as parseCrewId, crewMemberId, habitId } from '../../domain/ids.js';
import { crewService } from '../../app/crew.service.js';
import type { Ports } from '../../app/ports.js';
import { requireScope, requireSelfOrAdmin } from '../auth.js';
import { createCrewSchema, enrolSchema, parseBody, visibilitySchema } from '../schemas.js';
import { crewView, memberView, membershipView } from '../views.js';

/**
 * Crew routes. Creating a crew and moving people between them is an admin job;
 * a member decides their own sharing and nobody else can.
 */
export function registerCrewRoutes(app: FastifyInstance, ports: Ports): void {
  const crews = crewService(ports);

  app.post('/crews', async (request, reply) => {
    requireScope(request, 'admin');
    const body = parseBody(createCrewSchema, request.body);
    const created = await crews.create(body);
    return reply.status(201).send(crewView(created));
  });

  app.post('/crews/:crewId/members', async (request, reply) => {
    requireScope(request, 'admin');
    const params = request.params as { crewId: string };
    const body = parseBody(enrolSchema, request.body);
    const entry = await crews.enrol({
      crewId: parseCrewId(params.crewId),
      memberId: crewMemberId(body.memberId),
      from: body.from,
      role: body.role,
    });
    return reply.status(201).send(membershipView(entry));
  });

  app.get('/crews/:crewId/members', async (request) => {
    requireScope(request, 'read');
    const params = request.params as { crewId: string };
    const query = request.query as { on?: string };
    if (query.on === undefined) {
      throw ValidationError.field('on', 'is needed to read a crew');
    }
    const members = await crews.membersOfCrew(parseCrewId(params.crewId), query.on);
    return { members: members.map(memberView) };
  });

  app.put('/members/:memberId/visibility', async (request) => {
    const params = request.params as { memberId: string };
    const memberId = crewMemberId(params.memberId);
    requireSelfOrAdmin(request, memberId);
    requireScope(request, 'write');
    const body = parseBody(visibilitySchema, request.body);

    if (body.habitId !== undefined) {
      if (body.visibility === undefined) {
        throw ValidationError.field('visibility', 'is needed alongside a habit id');
      }
      const updated = await crews.setHabitVisibility(
        memberId,
        habitId(body.habitId),
        body.visibility,
      );
      return { fallback: updated.fallback, perHabit: [...updated.perHabit].map(pairOf) };
    }

    if (body.fallback === undefined) {
      throw ValidationError.field('fallback', 'is needed when no habit is named');
    }
    const updated = await crews.setVisibility(memberId, body.fallback);
    return { fallback: updated.fallback, perHabit: [...updated.perHabit].map(pairOf) };
  });
}

function pairOf(entry: readonly [string, string]): { habitId: string; visibility: string } {
  return { habitId: entry[0], visibility: entry[1] };
}
