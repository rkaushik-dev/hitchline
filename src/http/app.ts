import type { FastifyInstance } from 'fastify';
import type { Config } from '../config/env.js';
import type { Ports } from '../app/ports.js';
import { registerAuth } from './auth.js';
import { registerRoutes } from './routes/index.js';
import { buildServer } from './server.js';

/**
 * The whole api: a server, the token check, and every route. Tests build one
 * of these against a memory store and inject into it.
 */
export interface AppOptions {
  readonly config: Config;
  readonly ports: Ports;
}

export function buildApp(options: AppOptions): FastifyInstance {
  const app = buildServer(options);
  registerAuth(app, { tokens: options.config.tokens });
  registerRoutes(app, options.ports);
  return app;
}
