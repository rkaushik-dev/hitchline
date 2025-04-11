import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FastifyInstance } from 'fastify';
import {
  TooManyRequests,
  createLimiter,
  registerRateLimit,
} from '../../../../src/http/middleware/rate-limit.js';
import { buildServer } from '../../../../src/http/server.js';
import { readConfig } from '../../../../src/config/env.js';
import { memoryStore } from '../../../../src/infra/store/memory.js';
import { fixedClock, type MutableClock } from '../../../../src/infra/clock.js';
import { ValidationError } from '../../../../src/domain/errors.js';

describe('the bucket', () => {
  it('lets a burst through up to the capacity', () => {
    const limiter = createLimiter(3, 60);
    for (let index = 0; index < 3; index += 1) {
      expect(() => limiter.take('ada', 0)).not.toThrow();
    }
    expect(() => limiter.take('ada', 0)).toThrow(TooManyRequests);
  });

  it('refills over time', () => {
    const limiter = createLimiter(2, 60);
    limiter.take('ada', 0);
    limiter.take('ada', 0);
    expect(() => limiter.take('ada', 0)).toThrow();
    expect(() => limiter.take('ada', 1_000)).not.toThrow();
  });

  it('does not refill past the capacity', () => {
    const limiter = createLimiter(2, 60);
    limiter.take('ada', 0);
    expect(limiter.peek('ada')).toBe(1);
    limiter.take('ada', 600_000);
    expect(limiter.peek('ada')).toBe(1);
  });

  it('keeps callers apart', () => {
    const limiter = createLimiter(1, 60);
    limiter.take('ada', 0);
    expect(() => limiter.take('bo', 0)).not.toThrow();
    expect(limiter.size).toBe(2);
  });

  it('says how long to wait', () => {
    const limiter = createLimiter(1, 60);
    limiter.take('ada', 0);
    try {
      limiter.take('ada', 0);
      expect.unreachable('should have refused');
    } catch (error) {
      expect((error as TooManyRequests).retryAfterSeconds).toBeGreaterThan(0);
    }
  });

  it('refuses a nonsense configuration', () => {
    expect(() => createLimiter(0, 60)).toThrow(ValidationError);
    expect(() => createLimiter(5, 0)).toThrow(/above zero/);
  });
});

describe('over http', () => {
  let app: FastifyInstance;
  let clock: MutableClock;

  beforeEach(() => {
    clock = fixedClock('2025-04-11T09:00:00Z');
    app = buildServer({
      config: { ...readConfig({}), store: 'memory', logLevel: 'silent' },
      ports: memoryStore({ clock }),
    });
    registerRateLimit(app, { clock, capacity: 2, refillPerMinute: 60 });
    app.post('/things', () => ({ ok: true }));
    app.get('/things', () => ({ ok: true }));
  });

  afterEach(async () => {
    await app.close();
  });

  it('lets reads through however many there are', async () => {
    for (let index = 0; index < 5; index += 1) {
      const response = await app.inject({ method: 'GET', url: '/things' });
      expect(response.statusCode).toBe(200);
    }
  });

  it('turns a burst of writes away with 429', async () => {
    const headers = { authorization: 'Bearer ada-token-0123456789' };
    expect((await app.inject({ method: 'POST', url: '/things', headers })).statusCode).toBe(200);
    expect((await app.inject({ method: 'POST', url: '/things', headers })).statusCode).toBe(200);
    const third = await app.inject({ method: 'POST', url: '/things', headers });
    expect(third.statusCode).toBe(429);
    expect(third.headers['retry-after']).toBeDefined();
  });

  it('lets the caller back in once the bucket refills', async () => {
    const headers = { authorization: 'Bearer ada-token-0123456789' };
    await app.inject({ method: 'POST', url: '/things', headers });
    await app.inject({ method: 'POST', url: '/things', headers });
    clock.advance(2_000);
    expect((await app.inject({ method: 'POST', url: '/things', headers })).statusCode).toBe(200);
  });

  it('keeps two tokens apart', async () => {
    const ada = { authorization: 'Bearer ada-token-0123456789' };
    const bo = { authorization: 'Bearer bo-token-01234567890' };
    await app.inject({ method: 'POST', url: '/things', headers: ada });
    await app.inject({ method: 'POST', url: '/things', headers: ada });
    expect((await app.inject({ method: 'POST', url: '/things', headers: bo })).statusCode).toBe(
      200,
    );
  });

  it('answers a problem body rather than html', async () => {
    const headers = { authorization: 'Bearer ada-token-0123456789' };
    await app.inject({ method: 'POST', url: '/things', headers });
    await app.inject({ method: 'POST', url: '/things', headers });
    const refused = await app.inject({ method: 'POST', url: '/things', headers });
    expect(refused.json<{ code: string }>().code).toBe('conflict');
  });
});
