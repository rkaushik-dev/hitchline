import type { FastifyInstance } from 'fastify';
import { DomainError, ValidationError } from '../../domain/errors.js';
import type { Clock } from '../../app/ports.js';

/**
 * A token bucket per caller. The api sits behind a proxy in any real
 * deployment, but a handset with a retry loop can still hammer one process,
 * and a nudge that fires on every crew member at 06:00 is a burst by design.
 */
export interface RateLimitOptions {
  readonly clock: Clock;
  readonly capacity: number;
  readonly refillPerMinute: number;
  /** Reads the caller a bucket belongs to. Defaults to the bearer token. */
  readonly keyOf?: (authorization: string | undefined, ip: string) => string;
}

export interface Bucket {
  tokens: number;
  lastRefillMs: number;
}

export class TooManyRequests extends DomainError {
  readonly retryAfterSeconds: number;

  constructor(retryAfterSeconds: number) {
    super('conflict', `too many requests, try again in ${String(retryAfterSeconds)} seconds`);
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

export interface Limiter {
  take(key: string, nowMs: number): void;
  peek(key: string): number;
  readonly size: number;
}

export function createLimiter(capacity: number, refillPerMinute: number): Limiter {
  if (!Number.isInteger(capacity) || capacity < 1) {
    throw ValidationError.field('capacity', `must be a whole number above zero, got ${capacity}`);
  }
  if (!Number.isFinite(refillPerMinute) || refillPerMinute <= 0) {
    throw ValidationError.field('refillPerMinute', 'must be above zero');
  }
  const buckets = new Map<string, Bucket>();
  const perMs = refillPerMinute / 60_000;

  return {
    take(key: string, nowMs: number): void {
      const bucket = buckets.get(key) ?? { tokens: capacity, lastRefillMs: nowMs };
      const gained = (nowMs - bucket.lastRefillMs) * perMs;
      bucket.tokens = Math.min(capacity, bucket.tokens + gained);
      bucket.lastRefillMs = nowMs;

      if (bucket.tokens < 1) {
        buckets.set(key, bucket);
        const seconds = Math.max(1, Math.ceil((1 - bucket.tokens) / perMs / 1000));
        throw new TooManyRequests(seconds);
      }
      bucket.tokens -= 1;
      buckets.set(key, bucket);
    },

    peek(key: string): number {
      return Math.floor(buckets.get(key)?.tokens ?? capacity);
    },

    get size(): number {
      return buckets.size;
    },
  };
}

const WRITE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** Only writes are limited: a report that reloads is cheap and a check in is not. */
export function registerRateLimit(app: FastifyInstance, options: RateLimitOptions): void {
  const limiter = createLimiter(options.capacity, options.refillPerMinute);
  const keyOf =
    options.keyOf ??
    ((authorization, ip) => (authorization === undefined ? `ip:${ip}` : authorization));

  app.addHook('onRequest', (request, reply, done) => {
    if (!WRITE_METHODS.has(request.method)) {
      done();
      return;
    }
    try {
      limiter.take(keyOf(request.headers.authorization, request.ip), options.clock.now().epochMs);
      done();
    } catch (error) {
      if (error instanceof TooManyRequests) {
        void reply
          .status(429)
          .header('retry-after', String(error.retryAfterSeconds))
          .send({ code: 'conflict', message: error.message, details: [] });
        return;
      }
      done(error as Error);
    }
  });
}
