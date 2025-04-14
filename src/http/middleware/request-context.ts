import type { FastifyInstance, FastifyRequest } from 'fastify';
import { requestLine } from '../../infra/logger.js';

/**
 * A request id on every line. A crew member reporting that "the app said no"
 * can read the id off the screen, and that id has to appear on the log line
 * for the request that said it.
 */
export const REQUEST_ID_HEADER = 'x-request-id';

const ID_PATTERN = /^[A-Za-z0-9_-]{8,64}$/;

declare module 'fastify' {
  interface FastifyRequest {
    startedAtMs?: number;
  }
}

export interface RequestContextOptions {
  /** Mints an id when the caller did not send one. */
  readonly nextId: () => string;
  /** Reads a monotonic time in milliseconds. */
  readonly now?: () => number;
}

/** Keeps a caller supplied id only when it looks like one. */
export function readRequestId(header: unknown): string | undefined {
  if (typeof header !== 'string') return undefined;
  return ID_PATTERN.test(header) ? header : undefined;
}

export function registerRequestContext(app: FastifyInstance, options: RequestContextOptions): void {
  const now = options.now ?? (() => performance.now());

  app.addHook('onRequest', (request, reply, done) => {
    const supplied = readRequestId(request.headers[REQUEST_ID_HEADER]);
    const id = supplied ?? options.nextId();
    request.id = id;
    request.startedAtMs = now();
    void reply.header(REQUEST_ID_HEADER, id);
    done();
  });

  app.addHook('onResponse', (request, reply, done) => {
    const started = request.startedAtMs ?? now();
    request.log.info(
      { requestId: request.id, status: reply.statusCode },
      requestLine(request.method, request.url, reply.statusCode, now() - started),
    );
    done();
  });
}

/** How long a request has been running, for a handler that wants to know. */
export function elapsedMs(request: FastifyRequest, nowMs: number): number {
  const started = request.startedAtMs;
  return started === undefined ? 0 : Math.max(0, nowMs - started);
}
