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
  readonly burst?: number;
}

export interface BuiltApp {
  readonly app: FastifyInstance;
  readonly metrics: Metrics;
}

export const API_VERSION = '0.1.0';

const DEFAULT_BURST = 30;
const DEFAULT_WRITES_PER_MINUTE = 120;

export function buildAppWithMetrics(options: AppOptions): BuiltApp {
  const app = buildServer(options);
  const metrics = createMetrics();
  const served = metrics.counter('hitchline_requests_total', 'requests served by status');
  const latency = metrics.histogram(
    'hitchline_request_ms',
    'how long a request took in milliseconds',
    LATENCY_BOUNDS,
  );

  registerHardening(app, { allowedOrigins: options.allowedOrigins ?? [] });
  registerRequestContext(app, {
    nextId: () => options.ports.ids.next('CheckInId').replace('chk_', 'req-'),
  });
  registerRateLimit(app, {
    clock: options.ports.clock,
    capacity: options.burst ?? DEFAULT_BURST,
    refillPerMinute: options.writesPerMinute ?? DEFAULT_WRITES_PER_MINUTE,
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
    const report = await readiness({ ports: options.ports, config: options.config });
    return reply.status(statusFor(report)).send(report);
  });

  registerRoutes(app, options.ports);
  return { app, metrics };
}

export function buildApp(options: AppOptions): FastifyInstance {
  return buildAppWithMetrics(options).app;
}
