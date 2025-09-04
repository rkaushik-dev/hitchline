import type { FastifyInstance } from 'fastify';
import type { AuditLog } from '../../app/audit.js';
import { describeEvent } from '../../app/events.js';
import { formatInstant } from '../../domain/time/zone.js';
import { requireScope } from '../auth.js';
import { optionalWindowOf, queryMemberOf } from '../query.js';
import { pageBody, paginate, readPageRequest } from '../pagination.js';

/**
 * The audit trail over http. It is admin only and it is a read: nothing writes
 * here, because an audit line somebody can post is not an audit line.
 */
export function registerAuditRoutes(app: FastifyInstance, log: AuditLog): void {
  app.get('/audit', (request) => {
    requireScope(request, 'admin');
    const window = optionalWindowOf(request);
    const query = request.query as { memberId?: string; zone?: string };

    let entries = window === undefined ? log.all() : log.inWindow(window, query.zone ?? 'UTC');
    if (query.memberId !== undefined) {
      const memberId = queryMemberOf(request, 'read the audit trail');
      entries = entries.filter((entry) => entry.memberId === memberId);
    }

    const page = paginate([...entries].reverse(), readPageRequest(request.query), (entry) =>
      String(entry.sequence),
    );
    return pageBody(page, (entry) => ({
      sequence: entry.sequence,
      name: entry.name,
      at: formatInstant(entry.at),
      memberId: entry.memberId,
      habitId: entry.habitId ?? null,
      checkInId: entry.checkInId ?? null,
      crewId: entry.crewId ?? null,
      detail: entry.detail,
      line: describeEvent(entry),
    }));
  });
}
