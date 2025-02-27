import type { FastifyInstance } from 'fastify';
import { ValidationError } from '../../domain/errors.js';
import { crewMemberId } from '../../domain/ids.js';
import { formatClockRange } from '../../domain/time/clocktime.js';
import { nudgesService } from '../../app/nudges.service.js';
import type { Ports } from '../../app/ports.js';
import { requireScope, requireSelfOrAdmin } from '../auth.js';
import { parseBody, policySchema } from '../schemas.js';
import { nudgeView } from '../views.js';

/**
 * Nudge routes. The plan is a read: nothing is sent from here, a worker picks
 * the plan up and delivers it, and this endpoint is what it asks.
 */
export function registerNudgeRoutes(app: FastifyInstance, ports: Ports): void {
  const nudges = nudgesService(ports);

  app.get('/members/:memberId/nudges', async (request) => {
    const params = request.params as { memberId: string };
    const query = request.query as { window?: string };
    const memberId = crewMemberId(params.memberId);
    requireSelfOrAdmin(request, memberId);
    if (query.window === undefined) {
      throw ValidationError.field('window', 'is needed to plan nudges');
    }
    const planned = await nudges.plan(memberId, query.window);
    return { nudges: planned.map(nudgeView) };
  });

  app.get('/members/:memberId/nudge-policy', async (request) => {
    const params = request.params as { memberId: string };
    const memberId = crewMemberId(params.memberId);
    requireSelfOrAdmin(request, memberId);
    const policy = await nudges.policyFor(memberId);
    return {
      quietHours: formatClockRange(policy.quietHours),
      minGapMinutes: policy.minGapMinutes,
      maxPerDay: policy.maxPerDay,
      leadMinutes: policy.leadMinutes,
      roles: policy.roles,
      channel: policy.channel,
    };
  });

  app.put('/members/:memberId/nudge-policy', async (request) => {
    const params = request.params as { memberId: string };
    const memberId = crewMemberId(params.memberId);
    requireSelfOrAdmin(request, memberId);
    requireScope(request, 'write');
    const body = parseBody(policySchema, request.body);
    const policy = await nudges.setPolicy(memberId, body);
    return {
      quietHours: formatClockRange(policy.quietHours),
      minGapMinutes: policy.minGapMinutes,
      maxPerDay: policy.maxPerDay,
      leadMinutes: policy.leadMinutes,
      roles: policy.roles,
      channel: policy.channel,
    };
  });
}
