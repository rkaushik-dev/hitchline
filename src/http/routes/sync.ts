import type { FastifyInstance } from 'fastify';
import { syncService, type QueuedCheckIn } from '../../app/sync.service.js';
import type { Ports } from '../../app/ports.js';
import { requireScope, requireSelfOrAdmin } from '../auth.js';
import { memberOf } from '../query.js';
import { parseBody, syncSchema } from '../schemas.js';

/**
 * The endpoint a handset calls when it finds signal again. It answers per item
 * rather than for the batch, so a phone with one bad row can still clear the
 * other two hundred.
 */
export function registerSyncRoutes(app: FastifyInstance, ports: Ports): void {
  const sync = syncService(ports);

  app.post('/members/:memberId/sync', async (request) => {
    requireScope(request, 'write');
    const memberId = memberOf(request);
    requireSelfOrAdmin(request, memberId);
    const body = parseBody(syncSchema, request.body);

    const queued: QueuedCheckIn[] = body.items.map((item) => ({
      localId: item.localId,
      habitId: item.habitId,
      at: item.at,
      reading: item.reading,
      note: item.note,
      idempotencyKey: item.idempotencyKey,
    }));

    const report = await sync.push(memberId, queued);
    return {
      accepted: report.accepted,
      duplicates: report.duplicates,
      refused: report.refused,
      results: report.results.map((result) => ({
        localId: result.localId,
        outcome: result.outcome,
        checkInId: result.checkInId ?? null,
        message: result.message,
      })),
    };
  });
}
