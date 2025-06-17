import type { FastifyInstance } from 'fastify';
import { ValidationError } from '../../domain/errors.js';

/**
 * The headers a json api should send and the limits it should hold to. None of
 * this is clever; all of it is the sort of thing that only gets noticed in a
 * penetration test report six months after the fact.
 */
export const SECURITY_HEADERS: Readonly<Record<string, string>> = {
  'x-content-type-options': 'nosniff',
  'x-frame-options': 'DENY',
  'referrer-policy': 'no-referrer',
  'cross-origin-resource-policy': 'same-origin',
  'cache-control': 'no-store',
};

export interface HardeningOptions {
  /** Largest body accepted, in bytes. */
  readonly maxBodyBytes?: number;
  /** Origins allowed to call the api from a browser. */
  readonly allowedOrigins?: readonly string[];
}

export const DEFAULT_MAX_BODY_BYTES = 64 * 1024;

export function checkBodyLimit(bytes: number): void {
  if (!Number.isInteger(bytes) || bytes < 1_024 || bytes > 8 * 1024 * 1024) {
    throw ValidationError.field('maxBodyBytes', 'must be between a kilobyte and eight megabytes');
  }
}

export function isAllowedOrigin(origin: string | undefined, allowed: readonly string[]): boolean {
  if (origin === undefined) return false;
  return allowed.includes(origin);
}

export function registerHardening(app: FastifyInstance, options: HardeningOptions = {}): void {
  const allowed = options.allowedOrigins ?? [];
  checkBodyLimit(options.maxBodyBytes ?? DEFAULT_MAX_BODY_BYTES);

  app.addHook('onSend', (request, reply, payload, done) => {
    for (const [name, value] of Object.entries(SECURITY_HEADERS)) {
      void reply.header(name, value);
    }
    const origin = request.headers.origin;
    if (isAllowedOrigin(origin, allowed)) {
      void reply.header('access-control-allow-origin', origin ?? '');
      void reply.header('vary', 'origin');
    }
    done(null, payload);
  });

  app.options('/*', (request, reply) => {
    const origin = request.headers.origin;
    if (!isAllowedOrigin(origin, allowed)) {
      return reply.status(404).send({ code: 'not_found', message: 'no cors here', details: [] });
    }
    return reply
      .header('access-control-allow-methods', 'GET,POST,PUT,PATCH,DELETE')
      .header('access-control-allow-headers', 'authorization,content-type,x-request-id')
      .header('access-control-max-age', '600')
      .status(204)
      .send();
  });
}
