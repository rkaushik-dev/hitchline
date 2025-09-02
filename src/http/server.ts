import Fastify, { type FastifyInstance } from 'fastify';
import type { Config } from '../config/env.js';
import type { Ports } from '../app/ports.js';
import { loggerFor } from '../infra/logger.js';
import { problemFor, statusFor } from './errors.js';

/**
 * The http surface. Routes are registered by the modules that own them; this
 * file only sets the server up, maps errors and answers the health check.
 */
export interface ServerOptions {
  readonly config: Config;
  readonly ports: Ports;
}

export interface HealthBody {
  readonly status: 'ok';
  readonly at: string;
  readonly store: string;
}

export function buildServer(options: ServerOptions): FastifyInstance {
  const app = Fastify({
    logger: loggerFor(options.config),
    disableRequestLogging: options.config.logLevel !== 'trace',
  });

  app.setErrorHandler((error, _request, reply) => {
    const status = statusFor(error);
    if (status >= 500) {
      app.log.error({ err: error }, 'request failed');
    }
    return reply.status(status).send(problemFor(error));
  });

  app.setNotFoundHandler((request, reply) =>
    reply.status(404).send({
      code: 'not_found',
      message: `no route for ${request.method} ${request.url}`,
      details: [],
    }),
  );

  app.get('/health', () => {
    const body: HealthBody = {
      status: 'ok',
      at: new Date(options.ports.clock.now().epochMs).toISOString(),
      store: options.config.store,
    };
    return body;
  });

  return app;
}
