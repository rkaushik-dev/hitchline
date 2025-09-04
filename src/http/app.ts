import type { FastifyInstance } from 'fastify';
import type { Config } from '../config/env.js';
import type { Ports } from '../app/ports.js';
import { registerAuth } from './auth.js';
import { openApiDocument } from './manifest.js';
import { readiness, statusFor } from './readiness.js';
import { createMetrics, LATENCY_BOUNDS, type Metrics } from './metrics.js';
import { registerHardening } from './middleware/hardening.js';
import { registerRateLimit } from './middleware/rate-limit.js';
import { registerRequestContext } from './middleware/request-context.js';
import { registerRoutes } from './routes/index.js';
import { registerAuditRoutes } from './routes/audit.js';
import { auditEverything, createAuditLog, type AuditLog } from '../app/audit.js';
import { createBus } from '../app/events.js';
import { buildServer } from './server.js';

/**
 * The whole api: a server, a request id, the rate limit, the token check and
 * every route, in that order. Tests build one of these against a memory store
 * and inject into it.
 */
export interface AppOptions {
  readonly config: Config;
  readonly ports: Ports;
  /** Writes per minute allowed to one caller. */
  readonly writesPerMinute?: number;
  /** Origins a browser may call the api from. */
  readonly allowedOrigins?: readonly string[];
  /** An audit log to write into. One is made when none is given. */
  readonly audit?: AuditLog;
  readonly burst?: number;
}

export interface BuiltApp {
  readonly app: FastifyInstance;
  readonly metrics: Metrics;
  readonly audit: AuditLog;
  readonly ports: Ports;
}

export const API_VERSION = '1.0.0';

export function buildAppWithMetrics(options: AppOptions): BuiltApp {
  // Everything the services announce is audited, and the ports handed to the
  // routes are the ones carrying that bus, so a route cannot write without the
  // trail seeing it.
  const bus = options.ports.events ?? createBus();
  const audit = options.audit ?? createAuditLog();
  auditEverything(bus, audit);
  const ports: Ports = { ...options.ports, events: bus };
  const app = buildServer({ ...options, ports });
  const metrics = createMetrics();
  const served = metrics.counter('hitchline_requests_total', 'requests served by status');
  const latency = metrics.histogram(
    'hitchline_request_ms',
    'how long a request took in milliseconds',
    LATENCY_BOUNDS,
  );

  registerHardening(app, { allowedOrigins: options.allowedOrigins ?? [] });
  registerRequestContext(app, {
    nextId: () => ports.ids.next('CheckInId').replace('chk_', 'req-'),
  });
  registerRateLimit(app, {
    clock: ports.clock,
    capacity: options.burst ?? options.config.burst,
    refillPerMinute: options.writesPerMinute ?? options.config.writesPerMinute,
  });
  registerAuth(app, { tokens: options.config.tokens });

  app.addHook('onResponse', (request, reply, done) => {
    const labels = { method: request.method, status: String(reply.statusCode) };
    served.add(1, labels);
    latency.observe(reply.elapsedTime, { method: request.method });
    done();
  });

  app.get('/metrics', (_request, reply) =>
    reply.header('content-type', 'text/plain; version=0.0.4').send(metrics.render()),
  );

  app.get('/openapi.json', () => openApiDocument({ version: API_VERSION }));

  app.get('/ready', async (_request, reply) => {
    const report = await readiness({ ports, config: options.config });
    return reply.status(statusFor(report)).send(report);
  });

  registerRoutes(app, ports);
  registerAuditRoutes(app, audit);
  return { app, metrics, audit, ports };
}

export function buildApp(options: AppOptions): FastifyInstance {
  return buildAppWithMetrics(options).app;
}
