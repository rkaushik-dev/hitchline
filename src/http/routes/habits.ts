import type { FastifyInstance } from 'fastify';
import { ValidationError } from '../../domain/errors.js';
import { habitId as parseHabitId, crewMemberId } from '../../domain/ids.js';
import { habitsService } from '../../app/habits.service.js';
import type { Ports } from '../../app/ports.js';
import { requireScope, requireSelfOrAdmin } from '../auth.js';
import { pageBody, paginate, readPageRequest } from '../pagination.js';
import { library } from '../../domain/habit/template.js';
import { formatCadence } from '../../domain/habit/cadence.js';
import { formatTarget } from '../../domain/habit/target.js';
import {
  applyTemplateSchema,
  createHabitSchema,
  parseBody,
  updateHabitSchema,
} from '../schemas.js';
import { habitView } from '../views.js';

/**
 * Habit routes. A member may do anything to their own habits; anybody else
 * needs the admin scope, which is what a coordinator setting up a new crew has.
 */
export function registerHabitRoutes(app: FastifyInstance, ports: Ports): void {
  const habits = habitsService(ports);

  app.post('/habits', async (request, reply) => {
    requireScope(request, 'write');
    const body = parseBody(createHabitSchema, request.body);
    requireSelfOrAdmin(request, crewMemberId(body.memberId));
    const created = await habits.create({
      memberId: crewMemberId(body.memberId),
      name: body.name,
      cadence: body.cadence,
      target: body.target,
      startsOn: body.startsOn,
      endsOn: body.endsOn,
      tags: body.tags,
      graceDays: body.graceDays,
    });
    return reply.status(201).send(habitView(created));
  });

  app.post('/habits/from-template', async (request, reply) => {
    requireScope(request, 'write');
    const body = parseBody(applyTemplateSchema, request.body);
    requireSelfOrAdmin(request, crewMemberId(body.memberId));
    const created = await habits.applyTemplate({
      memberId: crewMemberId(body.memberId),
      key: body.key,
      startsOn: body.startsOn,
      endsOn: body.endsOn,
    });
    return reply.status(201).send(habitView(created));
  });

  app.get('/templates', (request) => {
    requireScope(request, 'read');
    return {
      templates: library().templates.map((template) => ({
        key: template.key,
        name: template.name,
        cadence: formatCadence(template.cadence),
        target: template.target === undefined ? null : formatTarget(template.target),
        tags: template.tags,
        note: template.note,
      })),
    };
  });

  app.get('/habits', async (request) => {
    const query = request.query as { memberId?: string };
    if (query.memberId === undefined) {
      throw ValidationError.field('memberId', 'is needed to list habits');
    }
    const memberId = crewMemberId(query.memberId);
    requireSelfOrAdmin(request, memberId);
    const found = await habits.listFor(memberId);
    const page = paginate(found, readPageRequest(request.query), (habit) => habit.id);
    return pageBody(page, habitView);
  });

  app.get('/habits/:habitId', async (request) => {
    const params = request.params as { habitId: string };
    const entry = await habits.get(parseHabitId(params.habitId));
    requireSelfOrAdmin(request, entry.memberId);
    return habitView(entry);
  });

  app.patch('/habits/:habitId', async (request) => {
    const params = request.params as { habitId: string };
    requireScope(request, 'write');
    const body = parseBody(updateHabitSchema, request.body);
    const id = parseHabitId(params.habitId);
    const existing = await habits.get(id);
    requireSelfOrAdmin(request, existing.memberId);

    let entry = existing;
    if (body.name !== undefined) entry = await habits.rename(id, body.name);
    if (body.cadence !== undefined) entry = await habits.changeCadence(id, body.cadence);
    if (body.target !== undefined) {
      entry = await habits.changeTarget(id, body.target ?? undefined);
    }
    if (body.tags !== undefined) entry = await habits.changeTags(id, body.tags);
    if (body.status !== undefined) entry = await habits.moveTo(id, body.status);
    return habitView(entry);
  });
}
