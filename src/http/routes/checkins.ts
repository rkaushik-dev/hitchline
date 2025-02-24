import type { FastifyInstance } from 'fastify';
import { NotFoundError } from '../../domain/errors.js';
import { checkInId as parseCheckInId, crewMemberId, habitId } from '../../domain/ids.js';
import { parseDateRange } from '../../domain/time/range.js';
import { checkInsService } from '../../app/checkins.service.js';
import { habitsService } from '../../app/habits.service.js';
import type { Ports } from '../../app/ports.js';
import { requireScope, requireSelfOrAdmin } from '../auth.js';
import { amendCheckInSchema, parseBody, recordCheckInSchema } from '../schemas.js';
import { checkInView, resolvedView } from '../views.js';

/**
 * Check in routes. Recording is the hot path: the handset posts one small body
 * and gets the stored row back, and a repeat of the same idempotency key gets
 * the first row rather than a second one.
 */
export function registerCheckInRoutes(app: FastifyInstance, ports: Ports): void {
  const checkIns = checkInsService(ports);
  const habits = habitsService(ports);

  app.post('/check-ins', async (request, reply) => {
    requireScope(request, 'write');
    const body = parseBody(recordCheckInSchema, request.body);
    const habit = await habits.get(habitId(body.habitId));
    requireSelfOrAdmin(request, habit.memberId);

    const result = await checkIns.record({
      habitId: habit.id,
      at: body.at,
      reading: body.reading,
      note: body.note,
      source: body.source,
      idempotencyKey: body.idempotencyKey,
    });
    return reply.status(result.accepted ? 201 : 200).send(checkInView(result.entry));
  });

  app.get('/members/:memberId/check-ins', async (request) => {
    const params = request.params as { memberId: string };
    const query = request.query as { window?: string };
    const memberId = crewMemberId(params.memberId);
    requireSelfOrAdmin(request, memberId);
    const window = query.window === undefined ? undefined : parseDateRange(query.window);
    const found = await checkIns.listFor(memberId, window);
    return { checkIns: found.map(resolvedView) };
  });

  app.post('/check-ins/:checkInId/void', async (request) => {
    const params = request.params as { checkInId: string };
    const id = parseCheckInId(params.checkInId);
    const existing = await ports.checkIns.byId(id);
    if (existing === undefined) throw new NotFoundError('check in', id);
    requireSelfOrAdmin(request, existing.memberId);
    requireScope(request, 'write');
    return checkInView(await checkIns.voidEntry(id));
  });

  app.patch('/check-ins/:checkInId', async (request) => {
    const params = request.params as { checkInId: string };
    requireScope(request, 'write');
    const body = parseBody(amendCheckInSchema, request.body);
    const id = parseCheckInId(params.checkInId);
    const existing = await ports.checkIns.byId(id);
    if (existing === undefined) throw new NotFoundError('check in', id);
    requireSelfOrAdmin(request, existing.memberId);
    const amended = await checkIns.amend(id, body.reading === null ? undefined : body.reading);
    return checkInView(amended);
  });
}
